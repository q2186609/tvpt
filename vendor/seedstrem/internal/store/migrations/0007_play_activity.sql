-- Playback activity: one row per torrent that has been streamed, so the
-- playback controller can pause idle downloads and expire torrents that
-- nobody has watched for the retention window.
--   last_play_at  unix seconds of the most recent stream session start
--   expires_at    unix seconds after which the torrent is removed
--   paused        1 when the download was paused for being idle
CREATE TABLE IF NOT EXISTS play_activity (
    hash         TEXT PRIMARY KEY,
    last_play_at INTEGER NOT NULL,
    expires_at   INTEGER NOT NULL,
    paused       INTEGER NOT NULL DEFAULT 0
);
