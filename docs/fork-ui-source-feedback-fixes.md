# Fork UI, LX imports and NetEase listening feedback

Verified on 2026-11-19. These changes build on `f6852034`.

## UI

- Custom sources now use the existing settings detail scaffold, section/header
  cards, buttons, fields, switches and density-aware dialogs. This gives Material
  and Clean the same behavior and geometry as the upstream settings pages.
- Import actions wrap at narrow widths and larger font scales. Tablet navigation
  stays visible and does not add a redundant detail back button.
- QQ/Kugou/Kuwo authorization cards live under Accounts. Source proxy controls
  live under Custom sources. Both reuse existing settings components.
- Remote platform libraries reuse the existing remote playlist row, including
  artwork, metadata, spacing, colors and offline image handling. QQ/Kugou/Kuwo
  song lists use the existing modern playlist song row.
- NetEase history artwork and text use the same theme-aware shapes and row
  typography as the existing playlist UI. FTP forms/dialogs use settings controls.

## LX import contract

Reference: `lyswhut/lx-music-mobile`, commit
`0669d1b6d419967822ff52ec06e50b9613bfd136`:

- `src/utils/data.ts`: leading block-comment metadata and per-field length limits.
- `src/core/userApi.ts`: importing/saving and selecting/executing are distinct.
- `src/screens/Home/Views/Setting/settings/Basic/UserApiEditModal/ScriptImportOnline.tsx`:
  online import and the 9,000,000-character budget.
- `android/app/src/main/assets/script/user-api-preload.js`: currentScriptInfo,
  initialization, action/quality declarations and default timer arguments.

Local file, pasted script and URL import now share one validation/persistence
pipeline. It strips a UTF-8 BOM, parses only the leading comment and accepts
obfuscated JavaScript without requiring plaintext `lx.on` or `musicUrl` markers.
The local budget is **9,000,000 UTF-8 bytes**, stricter than LX's character count
for non-ASCII input, and applies while reading as well as before saving.

Import does not execute JavaScript or wait for network initialization. New scripts
are saved disabled. Enabling initializes once, checks declared musicUrl platforms
and qualities, persists those capabilities and reuses that engine for playback.
Testing remains an explicit action. Existing enabled sources are not disabled by
an upgrade. Reimporting identical content preserves custom name, enabled state,
priority and import time; it can repair a missing script file.

Metadata adds homepage without invalidating older indexes. The common checked
atomic writer is used for persistence; the reader retains legacy `.bak` recovery.
Runtime metadata includes id/homepage, and setTimeout accepts its default delay
and callback arguments like LX Mobile.

## NetEase feedback transport

Historical commits `24be9b08` and `2f815c22` used desktop browser WEAPI uploads.
The previous code still used that transport. Changing only the User-Agent cannot
replace imported mobile cookies, and OkHttp's ordinary CookieJar can overwrite a
manually supplied Cookie header.

Current protocol reference: `NeteaseCloudMusicApiEnhanced/api-enhanced`:

- `module/scrobble.js`, last modifying commit
  `29c48e2ef98eb0a61e91a8d0fa41f65cf7ebb97c` (2026-10-19).
- `util/config.json`: dedicated clientlog domain.
- `util/request.js`: desktop osx device profile and EAPI encrypted header.

Uploads now use `https://clientlog.music.163.com/eapi/feedback/weblog`, with the
signed path `/api/feedback/weblog`. Both HTTP cookies and the encrypted header
carry one desktop profile: `os=osx`, appver `3.1.10.5100`, osver `15.5`, desktop UA
and an empty deviceId. Mobile serial/IMEI/sDeviceId cookies are not forwarded.
The dedicated client uses NO_COOKIES and disables redirects, preventing device
profile overwrite or authenticated log redirects. Other API requests and stored
login cookies are unchanged.

Both startplay and play include mainsite/mainsiteWeb. Real playback start,
listening duration, account isolation, ordered retry and delivery deduplication
remain in the existing playback/feedback chain. No synthetic extra listens are
generated. The play event uses playend and positive actual listened seconds.

### Validation boundary

Unit tests decrypt the EAPI request and check its signed path, checksum, header,
desktop cookie identity and unchanged mobile login context. Other tests cover
account switching, logout, ordered start/play, minimum listen time and duplicate
delivery. UI tests cover both themes, narrow layouts and real dialog input/cancel.

An unauthenticated empty-log transport probe on 2026-11-19 returned HTTP 200 and
`{"code":200,"data":"success","message":""}`. No account cookies, song IDs or
listening events were submitted. **This only verifies transport acceptance.** It
also demonstrates why a 200 response is not proof of history insertion.

No authenticated account was available to validate server-side history insertion.
That result remains unverified; the APK must not be described as having passed an
authenticated upload/history round-trip. A real-device check needs a logged-in
account, the listening-sync toggle enabled, actual playback for at least 30 seconds,
then a refresh of the cloud listening history (server processing may be delayed).

Focused regression results do not establish a green full test suite; unrelated
historical full-suite failures were recorded in the earlier audit.

## Verification record

- 154 focused JVM/Robolectric regressions passed: app 113, platform 34,
  local feedback 4, playback session timing 3. No failures or skips.
- 165 quality-tool self-tests passed; module-boundary and whitespace checks passed.
- Material/Clean light and dark installed-source pages were captured and visually
  reviewed, as was a 320dp, 1.3x-font source page. Screenshots stay under `.work`.
- Dialog text input and cancellation are asserted in both styles, using a 320dp
  test window to avoid Robolectric issue #8460; wider page layout remains covered.
- No physical device/emulator was connected (`adb devices` was empty).
