# Upstream sync and fork audit

## Revisions

- Fork base: `fe680a3bb2503e5c727bfb0e767265056c6ca5d5`
- Upstream snapshot: `7b4a47cb2ea76866968c6f6e7743d008cdbb7b9e`
- Strategy: preserve the upstream module split, storage/sync/playback architecture,
  and UI implementation; port fork-only platform APIs and UI through host
  dependencies rather than restoring the old `AppContainer` calls inside libraries.

## Preserved and integrated

- LX custom sources, script import/test, quality fallback and source priority.
- QQ Music, Kugou and Kuwo clients, cookie login, playback, playlist discovery,
  detail pages, search entry and listen-together stream resolution.
- FTP browse, secure settings and local playback; remote paths and partial
  downloads are guarded, and failed transfers are not cached as playable files.
- NetEase daily recommendations and full-list sheet, personal FM/radar and
  expandable recommendation lists from the fork, using upstream host layouts.
- NetEase recent-cloud-history view and the existing local-playlist import,
  deduplication and merge-to-account flow. Optional toggles also add local
  favorite-to-like and audible-playback scrobbling.
- Locally pinned continue-playing entries, persisted independently from synced
  counters/deletion state so upstream merge cannot erase user pins.

## Audit findings addressed

- Search-result ID lookup attempted to store `null` in `ConcurrentHashMap`,
  which throws. It now uses bounded positive/negative TTL entries, exact title,
  artist-set and duration matching, and propagates cancellation.
- A NetEase CDN cover URL was treated as proof of NetEase song identity. That
  could submit a third-party track under an unrelated numeric ID. Platform
  identity now requires explicit source/audio ID or an explicit NetEase lyric
  match; remote search requires a non-empty artist and duration-compatible hit.
- Periodic playback-stat flush marked the current cycle counted too early and
  lost the full listening duration needed by scrobble. Snapshots carry cumulative
  listen time and a stable play-session identifier.
- `startplay` is emitted on verified progress advance, not when play is merely
  requested. `play` is queued after it. Events are ordered, account-bound,
  idempotently receipted and held in an atomic bounded journal for retry.
- Cookie-only auth storage used asynchronous writes without checking persistence,
  and recovery could delete valid credentials after a key-storage fault. The
  added QQ/Kugou/Kuwo/FTP storage reuses account-safe fallback and checks durable
  writes before publishing an auth/config state.
- LX URL and script caches were keyed without all source identity fields;
  source-change and forced-refresh paths now invalidate the relevant keys.
  Deterministic per-track failures no longer cool down an entire source.
- LX source loading and UI imports used blocking WebView/stream reads on the
  caller thread. Engine initialization is serialized on IO; script/response
  sizes, navigation, URLs, and destroyed-engine callbacks are bounded.
- QQ/Kugou/Kuwo downloads resolve the requested platform rather than falling
  through to a NetEase ID. Playback cache keys include source, native ID,
  quality, and a hash of the resolved URL to avoid cross-source cache reuse.
- Custom source/export and playlist identity are carried through listen-together,
  downloads, recent items, and title matching. Bilibili metadata search is
  injected from the app host to respect module dependency direction.
- FTP retries no longer append to partial files or substitute a different
  same-sized remote file. Path traversal/control characters are rejected,
  listings no longer turn failed responses into empty directories, and scans
  track visited directories.

## Verification

- `gradle help`: passed.
- `:app:assembleDebug`: passed; final APK at `app/build/outputs/apk/debug/app-debug.apk`.
- `module_boundaries.py`: passed.
- Final targeted verification passed with zero failures: app (42), local (38),
  platform (287), network (46), playback logic (3), and listen-together (14).
- A separate full app JVM run completed 4032 tests with 20 failures and 1 skipped.
  This included pre-existing Windows/Robolectric filesystem, datastore and bitmap
  fixture failures. The three newly added library tabs and QQ search are opt-in
  to their display-order helpers, retaining the upstream default order.
- Upstream `YouTubeEjsWebViewFallbackSolverTest` fails on this Windows Robolectric
  environment because its temporary WebView directory is not recognized as an
  allowed internal-storage path. No production source for that upstream test
  was modified.
- Boundary checker identified that Bilibili search must be an app-host adapter,
  not a platform-library dependency on `BiliClient`; it is hosted under
  `app/core/integration/search` and injected into playback.

The upstream merge commit is on local branch `sync/upstream-20261118`; audit/fix
changes are staged but not committed. No remote changes have been pushed.
