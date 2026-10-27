package store

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

// PlayActivity is the playback bookkeeping kept for one torrent: when it
// was last streamed, when it expires, and whether its download was
// paused for being idle.
type PlayActivity struct {
	Hash       string
	LastPlayAt int64 // unix seconds
	ExpiresAt  int64 // unix seconds
	Paused     bool
}

const playActivityCols = `hash, last_play_at, expires_at, paused`

// NotePlay records the start of a playback session for hash and pushes
// the expiry out. The first play gets now+retention; every later play
// extends the remaining window by extend (never shortening it). It
// reports whether the torrent was paused for idleness before this play,
// so the caller can resume the download.
func (s *Store) NotePlay(ctx context.Context, hash string, now time.Time, retention, extend time.Duration) (resume bool, err error) {
	ts := now.Unix()
	var cur PlayActivity
	row := s.db.QueryRowContext(ctx,
		`SELECT `+playActivityCols+` FROM play_activity WHERE hash = ?`, hash)
	switch err := row.Scan(&cur.Hash, &cur.LastPlayAt, &cur.ExpiresAt, &cur.Paused); {
	case errors.Is(err, sql.ErrNoRows):
		cur.ExpiresAt = ts + int64(retention/time.Second)
	case err != nil:
		return false, err
	default:
		// Later plays keep the torrent for at least extend from now,
		// never shorter than what it already had. Using max() rather
		// than addition matters because one playback opens many
		// requests (every seek is a new session): adding per request
		// would balloon the deadline by days.
		if want := ts + int64(extend/time.Second); want > cur.ExpiresAt {
			cur.ExpiresAt = want
		}
		resume = cur.Paused
	}

	_, err = s.db.ExecContext(ctx,
		`INSERT INTO play_activity (`+playActivityCols+`) VALUES (?, ?, ?, 0)
		 ON CONFLICT(hash) DO UPDATE SET
		     last_play_at = excluded.last_play_at,
		     expires_at   = excluded.expires_at,
		     paused       = 0`,
		hash, ts, cur.ExpiresAt)
	if err != nil {
		return false, err
	}
	return resume, nil
}

// EnsurePlayActivity inserts a row for hash only when none exists,
// using at as the last-play time. It lets the playback sweep track
// torrents that predate this bookkeeping (or were adopted) instead of
// leaving them untracked forever.
func (s *Store) EnsurePlayActivity(ctx context.Context, hash string, at time.Time, retention time.Duration) error {
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO play_activity (`+playActivityCols+`) VALUES (?, ?, ?, 0)
		 ON CONFLICT(hash) DO NOTHING`,
		hash, at.Unix(), at.Unix()+int64(retention/time.Second))
	return err
}

// AllPlayActivity returns every tracked row, used by the playback sweep.
func (s *Store) AllPlayActivity(ctx context.Context) ([]PlayActivity, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT `+playActivityCols+` FROM play_activity ORDER BY last_play_at`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []PlayActivity
	for rows.Next() {
		var a PlayActivity
		if err := rows.Scan(&a.Hash, &a.LastPlayAt, &a.ExpiresAt, &a.Paused); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

// SetPlayPaused records whether the torrent's download is currently
// paused for idleness.
func (s *Store) SetPlayPaused(ctx context.Context, hash string, paused bool) error {
	v := 0
	if paused {
		v = 1
	}
	_, err := s.db.ExecContext(ctx, `UPDATE play_activity SET paused = ? WHERE hash = ?`, v, hash)
	return err
}

// DeletePlayActivity drops the bookkeeping row for hash, called when the
// torrent itself is removed.
func (s *Store) DeletePlayActivity(ctx context.Context, hash string) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM play_activity WHERE hash = ?`, hash)
	return err
}
