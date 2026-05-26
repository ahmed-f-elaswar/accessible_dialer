# Accessible Dialer

A native Android phone dialer built for accessibility. Kotlin + Jetpack Compose + Material 3.
Targets `minSdk 29` (Android 10) and supports being set as the device's **default phone app**, so it can place outgoing calls, manage the call log, and present a full‑screen incoming‑call UI.

## Features

- **Dialpad** — large 36 sp keys, DTMF tones, haptic feedback, long-press `0` for `+`, long-press backspace to clear, `tel:` URI deep-link prefill.
- **Recent calls** — incoming / outgoing / missed / rejected, formatted timestamps, one‑tap call back.
- **Contacts** — searchable list backed by `ContactsContract`, deduped per contact, one‑tap call.
- **Favorites** — starred contacts only.
- **Incoming / outgoing call screen** — full screen, shows on lock screen, 96 dp answer / decline / hangup buttons, mid‑call mute / speaker / hold.
- **Default Dialer integration** — `RoleManager.ROLE_DIALER` request, `InCallService` to handle live calls.

## Accessibility highlights

- Minimum 48 dp touch targets; primary actions are 88–96 dp.
- Every interactive element has a TalkBack `contentDescription` (e.g. "2 A B C", "Call John Smith").
- Number display reads digits individually ("Number: 0 7 7 7 …") so screen readers don't run them together.
- Call status is a Compose `liveRegion` so transitions (ringing → active → ended) are announced automatically.
- Type uses `sp` everywhere; honors the OS user font scale.
- High-contrast Material 3 palette with separate light/dark schemes.
- Haptic feedback on every key press to support users who can't hear DTMF tones.

## Project layout

```
app/
├── build.gradle.kts
└── src/main/
    ├── AndroidManifest.xml
    ├── java/com/accessible/dialer/
    │   ├── DialerApplication.kt
    │   ├── MainActivity.kt
    │   ├── call/                # InCallService, InCallActivity, in-call UI, call holder
    │   ├── ui/
    │   │   ├── DialerApp.kt     # Bottom nav + setup banner
    │   │   ├── dialpad/         # Dialpad UI + DTMF tones
    │   │   ├── recents/         # Call log
    │   │   ├── contacts/        # Contacts + search
    │   │   ├── favorites/       # Starred contacts
    │   │   └── theme/           # Material3 colors + typography
    │   └── util/                # Permissions + default-dialer helpers
    └── res/                     # strings, themes, launcher icon
```

## Building

1. Install **Android Studio Iguana (or newer)** and the Android SDK platform for API 34.
2. Generate the Gradle wrapper (one-time, requires Gradle 8.7+ on `PATH`):
   ```powershell
   gradle wrapper --gradle-version 8.7
   ```
3. Open the folder in Android Studio and let it sync, or from the command line:
   ```powershell
   .\gradlew.bat assembleDebug
   ```
4. Install on a connected device / emulator running Android 10+:
   ```powershell
   .\gradlew.bat installDebug
   ```

## Using the app

1. Launch **Accessible Dialer** on the device.
2. Grant all requested permissions (phone, call log, contacts, notifications).
3. Tap **Set as default** in the banner and accept the system dialog. Until this is done, incoming-call UI and full call control aren't available — Android only routes call events to the default dialer.
4. Use the bottom tabs to switch between Keypad, Recent calls, Contacts and Favorites.

## Notes / limitations

- This is a basic dialer feature-set: a single live call is tracked at a time (no conferences / call-waiting merging UI).
- Marking a contact as a favorite is done from the system contacts app; this dialer reads the `starred` flag but doesn't edit it.
- Emergency calls always go through the platform regardless of which dialer is selected.
- Tested only at the source-compile level — once you have an Android Studio environment, build and run on a real device for end-to-end verification.
