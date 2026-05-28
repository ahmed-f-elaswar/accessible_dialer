# Accessible Dialer

A native Android phone dialer built first and foremost for accessibility — large
touch targets, TalkBack-aware semantics, scalable text, high-contrast Material 3
theming, and explicit verbal feedback for state changes.

Kotlin · Jetpack Compose · Material 3 · `minSdk 29` (Android 10) · `targetSdk 34`.

Supports being set as the device's **default phone app** so it can place outgoing
calls, manage the call log, intercept ringer volume keys, and present a
full-screen incoming-call UI.

## Author

**Ahmed Farid** — [a.f.elaswar@gmail.com](mailto:a.f.elaswar@gmail.com)

The same attribution appears in **Settings → About** inside the app.

## Feature overview

### Calling
- Dialpad with 36 sp keys, DTMF tones, haptic feedback, long-press `0` for `+`,
  long-press backspace to clear, `tel:` deep-link prefill.
- Speed dial: long-press digits 1–9 on the dialpad to call a bound contact.
- Outgoing / incoming call screen, shown on the lock screen with 96 dp answer /
  decline / hangup buttons. Mid-call mute, speaker, hold, keypad, and DTMF.
- Volume-key ringer silencer during an incoming call.
- Multi-SIM / calling-account selection ("Ask each time" or a fixed account).

### Contacts
- Searchable directory backed by `ContactsContract`, deduped per contact,
  one-tap call, copy number, send SMS, share, set ringtone, block, favorite.
- Alphabetic section index with expand/collapse headers.
- Multi-select mode (long-press to enter) with bulk **Delete**, **Share**, and
  **Move to account** actions.
- Per-account filter so you can hide SIM, Google, Exchange, or local-only
  contacts independently.
- Create / edit contact, including phone numbers, emails, company, and starred
  flag.
- Name normaliser (Tools): batch-clean odd casing, stray whitespace, and
  trailing punctuation across the whole address book.
- Contact import / export (Tools): vCard 3.0 round-trip.

### Recents
- Incoming / outgoing / missed / rejected with formatted relative timestamps
  bucketed by Today / Yesterday / This week / Earlier.
- One-tap call back, view contact details, copy number, delete a single entry
  or the entire history entry for a number.
- Newest activity always rises to the top, even after pull-to-refresh.

### Favorites
- Starred contacts only, same row interactions as Contacts.

### Default-dialer integration
- `RoleManager.ROLE_DIALER` request with an in-app setup banner.
- `InCallService` to handle live calls; full-screen incoming-call activity.
- Custom ringer (`Ringer.kt`) that respects quiet hours and silent mode.

### In-app update checker
- Polls a published [`latest.json`](latest.json) manifest, compares
  `versionCode`, and offers a one-tap download of the signed APK from the
  matching GitHub release.
- Triggered manually from **Settings → Check for updates**.

## Accessibility highlights

- Minimum 48 dp touch targets; primary actions are 88–96 dp.
- Every interactive element has a `contentDescription` (e.g. "2 A B C", "Call
  John Smith").
- Number display reads digits individually ("Number: 0 7 7 7 …") so screen
  readers don't run them together. Toggleable in Settings.
- Call status is a Compose `liveRegion` so transitions (ringing → active →
  ended) are announced automatically.
- Type uses `sp` everywhere; honors the OS user-font-scale and additionally
  exposes a Small / Default / Large / Extra-large preference.
- High-contrast Material 3 palette with light / dark / system themes.
- Haptic feedback on every key press to support users who can't hear DTMF
  tones.
- After returning from a sub-screen, focus is restored to the previously opened
  list row (Contacts and Recents) instead of being lost to the search field.
- Scroll position is preserved across sub-screen navigation.

## Project layout

```
app/
├── build.gradle.kts
└── src/main/
    ├── AndroidManifest.xml
    ├── java/com/accessible/dialer/
    │   ├── DialerApplication.kt
    │   ├── MainActivity.kt
    │   ├── call/                # InCallService, InCallActivity, in-call UI,
    │   │                        # ringer, volume-key interceptor, call holder
    │   ├── settings/            # DataStore-backed settings repository
    │   ├── ui/
    │   │   ├── DialerApp.kt          # Root host: bottom nav, sub-screen routes,
    │   │   │                          # hoisted list state + focus-return state
    │   │   ├── dialpad/              # Dialpad UI + DTMF tones
    │   │   ├── recents/              # Call log
    │   │   ├── contacts/             # Contacts, search, multi-select,
    │   │   │                          # details, edit, account filter
    │   │   ├── favorites/            # Starred contacts
    │   │   ├── settings/             # Settings hub + sub-screens
    │   │   └── theme/                # Material 3 colors + typography
    │   └── util/                # ContactOps, ContactAccounts, RowActions,
    │                            # PhoneAccounts, UpdateChecker, Updater
    └── res/                     # strings, themes, launcher icon
latest.json                       # Update manifest consumed by UpdateChecker
```

## Building

1. Install **Android Studio Iguana (or newer)** and the Android SDK platform for
   API 34.
2. Open the folder in Android Studio and let it sync, or from the command line:
   ```powershell
   .\gradlew.bat assembleDebug
   ```
3. Install on a connected device / emulator running Android 10+:
   ```powershell
   .\gradlew.bat installDebug
   ```
4. Signed release builds require a `keystore.properties` at the repo root with
   `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. Without it, the
   release variant is built unsigned.
   ```powershell
   .\gradlew.bat assembleRelease
   ```

## Using the app

1. Launch **Accessible Dialer** on the device.
2. Grant all requested permissions (phone, call log, contacts, notifications).
3. Tap **Set as default** in the banner and accept the system dialog. Until
   this is done, the incoming-call UI and full call control aren't available —
   Android only routes call events to the default dialer.
4. Use the bottom tabs to switch between Keypad, Recents, Contacts, and
   Favorites.
5. **Settings** lives in the top-app-bar overflow. Display, Calling,
   Accessibility, Blocking, and Tools each have their own sub-screen.

## Releasing

1. Bump `versionName` and `versionCode` in [app/build.gradle.kts](app/build.gradle.kts).
2. `./gradlew.bat assembleRelease` — output at
   `app/build/outputs/apk/release/app-release.apk`.
3. Update [latest.json](latest.json) with the new `versionName`,
   `versionCode`, `tag`, `apkUrl`, and `notes`.
4. Commit, tag (`git tag -a vX.Y.Z -m "vX.Y.Z"`), push tags.
5. Create the GitHub release and attach the APK:
   ```powershell
   gh release create vX.Y.Z app\build\outputs\apk\release\app-release.apk `
     --title "vX.Y.Z" --notes "..."
   ```

The in-app update checker (Settings → Check for updates) will pick up the new
version on the next poll.

## Notes / limitations

- Basic dialer scope: a single live call at a time (no conference / call-waiting
  merge UI).
- Marking a contact as a favorite is also possible from the system contacts app;
  this dialer reads and writes the `starred` flag and stays in sync.
- Emergency calls always go through the platform regardless of which dialer is
  selected.
- Voice-changing effects during a call (e.g. helium pitch) are intentionally
  **not** supported — Android does not expose the cellular uplink audio path to
  third-party apps.

## License

Personal / portfolio project. Contact [a.f.elaswar@gmail.com](mailto:a.f.elaswar@gmail.com)
before redistributing.
