package stremio

import (
	"errors"
	"net/http"
	"strconv"
	"strings"

	"github.com/go-chi/chi/v5"

	"github.com/kipsilabs/seedstrem/internal/metainfo"
	"github.com/kipsilabs/seedstrem/internal/prowlarr"
	"github.com/kipsilabs/seedstrem/internal/torrents"
)

// play handles GET|HEAD /play/{infohash} — the resolve half. It adds the
// magnet to qBittorrent, waits for metadata, selects the matching file,
// and 302-redirects to the /dl streaming endpoint.
func (h *Handler) play(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	s := h.settings()
	infohash := strings.ToLower(chi.URLParam(r, "infohash"))
	magnet := r.URL.Query().Get("magnet")

	// Deferred magnet-less release: the stream list did not resolve its
	// infohash, because each such result costs a .torrent round trip and
	// Prowlarr serializes them per indexer (a 9-result page took 12-18s,
	// which made Stremio report the addon as missing). Resolve it now, on
	// the actual play — a single round trip behind a spinner the user
	// already expects.
	var torrentFile []byte
	if dl := r.URL.Query().Get("dl"); dl != "" {
		resolvedMagnet, raw, err := h.prowlarr(s).ResolveDownloadURL(ctx, prowlarr.Result{
			Title:       r.URL.Query().Get("t"),
			DownloadURL: dl,
		})
		if err != nil {
			h.logger.Error("stremio: deferred resolve failed", "error", err)
			http.Error(w, "failed to resolve stream", http.StatusBadGateway)
			return
		}
		hash, _, err := metainfo.FromMagnet(resolvedMagnet)
		if err != nil {
			http.Error(w, "failed to resolve stream", http.StatusBadGateway)
			return
		}
		magnet = resolvedMagnet
		infohash = hash
		torrentFile = raw
	}

	if magnet == "" {
		http.Error(w, "missing magnet", http.StatusBadRequest)
		return
	}
	// Guard: the magnet must match the path infohash. A deferred play has
	// no infohash in the path (it is only known after resolution), so the
	// comparison applies only when one was supplied.
	if hash, _, err := metainfo.FromMagnet(magnet); err != nil {
		http.Error(w, "invalid magnet", http.StatusBadRequest)
		return
	} else if !strings.HasPrefix(strings.ToLower(chi.URLParam(r, "infohash")), "dl-") && hash != infohash {
		http.Error(w, "magnet does not match infohash", http.StatusBadRequest)
		return
	}

	sel := torrents.Selector{
		// Content identity (empty when the stream URL predates it) — used
		// only to persist what this torrent was added for, so later stream
		// requests can surface it as already-owned.
		Source:     r.URL.Query().Get("src"),
		ContentRef: r.URL.Query().Get("cid"),
		// Prowlarr indexer this release came from (empty for stream URLs
		// that predate it) — persisted so cleanup can apply the indexer's
		// own seed time.
		Indexer:   r.URL.Query().Get("ix"),
		UserAgent: r.UserAgent(),
	}
	if r.URL.Query().Get("series") == "1" {
		sel.IsSeries = true
		sel.Season, _ = strconv.Atoi(r.URL.Query().Get("s"))
		sel.Episode, _ = strconv.Atoi(r.URL.Query().Get("e"))
	}
	h.logger.Debug("stremio: play resolve",
		"infohash", infohash, "series", sel.IsSeries, "season", sel.Season, "episode", sel.Episode)

	// If we cached the raw .torrent for this infohash while building the
	// stream list, add it directly (skips qBittorrent's metadata fetch);
	// otherwise fall back to the magnet. A deferred play already has the
	// bytes from its own resolve above.
	if len(torrentFile) == 0 {
		torrentFile = h.torrentFiles.get(infohash)
	}
	link, err := h.svc.Resolve(ctx, magnet, torrentFile, sel)
	if err != nil {
		switch {
		case errors.Is(err, torrents.ErrMetadataTimeout):
			w.Header().Set("Retry-After", "5")
			http.Error(w, "torrent metadata not ready yet", http.StatusServiceUnavailable)
		case errors.Is(err, torrents.ErrNoFileMatch):
			http.Error(w, "no matching file in torrent", http.StatusNotFound)
		default:
			h.logger.Error("stremio: resolve failed", "infohash", infohash, "error", err)
			http.Error(w, "failed to resolve stream", http.StatusBadGateway)
		}
		return
	}

	target := strings.TrimSuffix(s.ExternalURL, "/") + "/dl/" + link.Token
	h.logger.Debug("stremio: play resolved",
		"infohash", infohash, "file", link.Path, "token", link.Token)
	http.Redirect(w, r, target, http.StatusFound)
}
