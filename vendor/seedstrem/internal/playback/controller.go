// Package playback owns the playback-aware download policy: it records
// when each torrent is watched, pauses downloads nobody is watching any
// more, and removes torrents that have gone unwatched past the retention
// window.
//
// The rules it implements, in the order they matter:
//   - while a stream is open the torrent downloads, prioritising the
//     file being played (that priority work lives in internal/torrents);
//   - when the last stream closes, the torrent keeps downloading for a
//     short grace period (so a pause/seek/reconnect in the player does
//     not thrash the client) and is then paused;
//   - every play pushes the expiry out by Extend, the first play sets it
//     to Retention, and an expired torrent is removed with its files.
package playback

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"github.com/kipsilabs/seedstrem/internal/downloader"
	"github.com/kipsilabs/seedstrem/internal/playsession"
	"github.com/kipsilabs/seedstrem/internal/store"
	"github.com/kipsilabs/seedstrem/internal/torrents"
)

// Settings is the live configuration slice the controller reads per call.
type Settings struct {
	// IdlePause is how long a torrent may sit unwatched before its
	// download is paused. <= 0 disables idle pausing.
	IdlePause time.Duration
	// Retention is the expiry window granted by a torrent's first play.
	// <= 0 disables expiry entirely.
	Retention time.Duration
	// Extend is how much every subsequent play pushes the expiry out.
	Extend time.Duration
}

// Controller tracks playback activity and reconciles the download client
// with the policy on a timer.
type Controller struct {
	store    *store.Store
	dc       downloader.Client
	svc      *torrents.Service
	sessions *playsession.Sessions
	settings func() Settings
	logger   *slog.Logger
	interval time.Duration
}

// New builds a Controller. interval is the reconciliation period; a zero
// or negative value falls back to 30s.
func New(st *store.Store, dc downloader.Client, svc *torrents.Service, sessions *playsession.Sessions, settings func() Settings, logger *slog.Logger, interval time.Duration) *Controller {
	if logger == nil {
		logger = slog.Default()
	}
	if interval <= 0 {
		interval = 30 * time.Second
	}
	return &Controller{store: st, dc: dc, svc: svc, sessions: sessions, settings: settings, logger: logger, interval: interval}
}

// NotePlay records the start of a playback session for hash and resumes
// the download if it had been paused for idleness. It never returns an
// error: playback must not fail because bookkeeping did, so problems are
// logged.
func (c *Controller) NotePlay(ctx context.Context, hash string) {
	s := c.settings()
	if s.Retention <= 0 && s.IdlePause <= 0 {
		return
	}
	resume, err := c.store.NotePlay(ctx, hash, time.Now(), s.Retention, s.Extend)
	if err != nil {
		c.logger.Warn("playback: record play failed", "hash", hash, "error", err)
		return
	}
	if !resume {
		// The bookkeeping flag can drift from the client (a reset
		// database, a client restart, a manual pause), so trust the
		// client's own state as well: anything stopped gets started
		// when somebody asks to watch it.
		if info, err := c.dc.Torrent(ctx, hash); err == nil && info.State == downloader.StatePaused {
			resume = true
		}
	}
	if !resume {
		return
	}
	if err := c.dc.Start(ctx, hash); err != nil {
		c.logger.Warn("playback: resume idle-paused torrent failed", "hash", hash, "error", err)
		return
	}
	if err := c.store.SetPlayPaused(ctx, hash, false); err != nil {
		c.logger.Warn("playback: clear paused flag failed", "hash", hash, "error", err)
	}
	c.logger.Info("playback: resumed torrent for playback", "hash", hash)
}

// Run reconciles on a ticker until ctx is cancelled.
func (c *Controller) Run(ctx context.Context) {
	s := c.settings()
	c.logger.Info("playback: policy active",
		"idlePause", s.IdlePause, "retention", s.Retention, "extend", s.Extend,
		"interval", c.interval)
	ticker := time.NewTicker(c.interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := c.Tick(ctx); err != nil && ctx.Err() == nil {
				c.logger.Warn("playback: sweep failed", "error", err)
			}
		}
	}
}

// Tick performs one reconciliation pass over tracked torrents.
func (c *Controller) Tick(ctx context.Context) error {
	s := c.settings()
	if s.Retention <= 0 && s.IdlePause <= 0 {
		return nil
	}
	if s.Retention > 0 {
		c.backfill(ctx, s.Retention)
	}

	rows, err := c.store.AllPlayActivity(ctx)
	if err != nil {
		return err
	}
	c.logger.Debug("playback: tick", "tracked", len(rows))
	now := time.Now()
	for _, row := range rows {
		if err := ctx.Err(); err != nil {
			return err
		}
		// A torrent that is being watched is never paused or removed,
		// whatever its deadline says.
		if c.sessions.Active(row.Hash) {
			continue
		}
		info, err := c.dc.Torrent(ctx, row.Hash)
		if errors.Is(err, downloader.ErrTorrentNotFound) {
			// The torrent is gone from the client; drop the bookkeeping
			// so it does not resurrect on the next pass.
			if err := c.store.DeletePlayActivity(ctx, row.Hash); err != nil {
				c.logger.Warn("playback: drop orphan activity failed", "hash", row.Hash, "error", err)
			}
			continue
		}
		if err != nil {
			c.logger.Warn("playback: read torrent failed", "hash", row.Hash, "error", err)
			continue
		}

		if s.Retention > 0 && row.ExpiresAt <= now.Unix() {
			c.remove(ctx, row.Hash, info)
			continue
		}

		if s.IdlePause > 0 && !row.Paused && info.Progress < 1 &&
			now.Sub(time.Unix(row.LastPlayAt, 0)) >= s.IdlePause {
			if err := c.dc.Pause(ctx, row.Hash); err != nil {
				c.logger.Warn("playback: pause idle torrent failed", "hash", row.Hash, "error", err)
				continue
			}
			if err := c.store.SetPlayPaused(ctx, row.Hash, true); err != nil {
				c.logger.Warn("playback: record paused flag failed", "hash", row.Hash, "error", err)
			}
			c.logger.Info("playback: paused idle download",
				"hash", row.Hash, "progress", info.Progress,
				"idle", now.Sub(time.Unix(row.LastPlayAt, 0)).Round(time.Second))
		}
	}
	return nil
}

// backfill makes sure every torrent known to seedstrem has an activity
// row, so torrents that predate this bookkeeping (or were adopted) are
// subject to the same idle/expiry rules. Their clock starts at the time
// they were added.
func (c *Controller) backfill(ctx context.Context, retention time.Duration) {
	torrents, err := c.store.AllTorrents(ctx)
	if err != nil {
		c.logger.Warn("playback: list torrents for backfill failed", "error", err)
		return
	}
	for _, tor := range torrents {
		at := time.Unix(tor.AddedAt, 0)
		if tor.AddedAt <= 0 {
			at = time.Now()
		}
		if err := c.store.EnsurePlayActivity(ctx, tor.Hash, at, retention); err != nil {
			c.logger.Warn("playback: backfill activity failed", "hash", tor.Hash, "error", err)
		}
	}
}

// remove deletes an expired torrent along with its files and bookkeeping.
func (c *Controller) remove(ctx context.Context, hash string, info downloader.TorrentInfo) {
	tor, err := c.store.TorrentByHash(ctx, hash)
	if err != nil {
		c.logger.Warn("playback: expired torrent missing from store", "hash", hash, "error", err)
		if delErr := c.store.DeletePlayActivity(ctx, hash); delErr != nil {
			c.logger.Warn("playback: drop activity failed", "hash", hash, "error", delErr)
		}
		return
	}
	if err := c.svc.Remove(ctx, tor, store.DeletionEvent{
		Reason:   store.DeleteReasonIdleExpired,
		Progress: info.Progress,
	}); err != nil {
		c.logger.Warn("playback: remove expired torrent failed", "hash", hash, "error", err)
		return
	}
	if err := c.store.DeletePlayActivity(ctx, hash); err != nil {
		c.logger.Warn("playback: drop activity after removal failed", "hash", hash, "error", err)
	}
	c.logger.Info("playback: removed unwatched torrent", "hash", hash, "name", tor.Name)
}
