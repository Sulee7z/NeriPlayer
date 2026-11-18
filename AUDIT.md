# Upstream sync and fork audit

## Revisions

- Fork base: `fe680a3bb2503e5c727bfb0e767265056c6ca5d5`
- Upstream snapshot: `7b4a47cb2ea76866968c6f6e7743d008cdbb7b9e`
- Local upstream merge: `fe680a3b` (two parents: fork and upstream)
- Local audit integration: `c840d008`
- Local post-audit hardening: `c0c93004`
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
  requested. `play` is queued after it. Events are account-bound and held in an
  atomic bounded journal; successful play sessions are recorded to suppress
  duplicate counters. Retries are best-effort, not a server-side exactly-once
  guarantee.
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
- A full app JVM run completed 4032 tests with 20 failures and 1 skipped on this
  Windows host. Failures span Robolectric storage/URI assumptions, filesystem and
  image fixtures, and a UI rendering test. Do not treat the entire suite as passing
  on this host; the upstream-only `YouTubeEjsWebViewFallbackSolverTest` also hits
  the Windows internal-storage sandbox limitation noted below.
- Upstream `YouTubeEjsWebViewFallbackSolverTest` fails on this Windows Robolectric
  environment because its temporary WebView directory is not recognized as an
  allowed internal-storage path. No production source for that upstream test
  was modified.
- Boundary checker passed after placing Bilibili search in the app host adapter at
  `app/src/main/java/moe/ouom/neriplayer/core/integration/search` and injecting
  it through playback dependencies.

The merged branch is `sync/upstream-20261118`. Audit changes are local commits;
no remote changes have been pushed.
