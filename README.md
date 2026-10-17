# Spam-Call-Blocker

An Android app that screens incoming calls and blocks Indian spam calls before your
phone rings. Everything runs on the phone: there is no account, no server, no
analytics, and your call data never leaves the device. See the
[specification](docs/SPEC.md) for the full design.

> **Android only.** The app needs Android 13 or newer. There is no iOS version yet.
> It isn't on the Play Store, so you install it yourself ([sideloading](#install-on-your-phone-sideload)).

## Features

### Call screening
The app becomes your phone's **Caller ID & spam app** (Android's call screening
role) and decides on every incoming call in a few milliseconds. Each block rule
uses one of three actions:

| Action | What happens |
|---|---|
| **Reject** | The call is ended before it rings. It still shows in your call log. |
| **Silence** | The call arrives without ringing and shows as missed. |
| **Warn** | The call rings normally, with a "Possible spam" notification. |

Rules are checked in order, and the first match wins. Allow rules always come
before block rules, so a real caller is hard to lose:

**Always let through**
- Saved contacts (Android never sends these to a spam app anyway).
- Numbers and prefixes on your **allowlist**.
- **TRAI 160-series** numbers (banks, government and other registered service senders).
- Numbers **you called** in the last 7 days.
- **Repeat callers**: a second call from the same number within 5 minutes rings, in
  case it's someone urgent.

**Blocked**
- Your own **block list** (Reject by default).
- Your **wildcard rules**, such as `98765*`, `*4321` or `+91987654321?`. The app shows how many
  numbers a rule covers and warns before saving a very broad one.
- **Prefix suggestions** you've accepted (Silence by default).
- **Remote spam lists** you subscribe to (Silence by default).
- **TRAI 140-series** promotional telemarketers (Reject by default).
- **Hidden / private numbers** (Silence by default).
- **International numbers**, meaning anything not +91 (Silence by default).

Every switch, time window and default action can be changed in Settings.

### Learning from your calls
- **Import on first run:** numbers already blocked in your phone's call log are
  offered as a checklist and added to your block list. You can re-run this from Settings.
- **Call log screen:** recent calls with the app's decision, plus **Block** and
  **Not spam** buttons.
- **After-call prompt:** after a call from an unknown number, a notification asks
  "Block this number?" with **Block** / **Not spam** buttons. You can turn this off.
- **Prefix suggestions:** when you've blocked several numbers from the same narrow
  range (by default 3 numbers sharing their first 8 digits, a range of 100 numbers),
  the app suggests blocking the range. Nothing changes until you accept a suggestion.
  Your contacts, your allowlist and numbers you've called are never covered.

### Remote spam lists
- Subscribe to any number list by URL: plain text, CSV or JSON. Entries ending in
  `*` act as prefixes. No lists are built in; you choose your sources.
- Lists refresh automatically (every 6 h, 12 h, 24 h or 7 days, or only when you
  tap refresh), on Wi-Fi only by default.
- A broken download never wipes a list: an empty list, one that is mostly unreadable,
  or one larger than 10 MB or 500,000 entries is rejected, and the last good copy is kept.
- The Sources screen shows each list's size, last update, any error, and how
  often you marked its numbers "Not spam" (its false positives).

### History and notifications
- **Home:** whether protection is active (with a fix button if it isn't), the number
  of calls blocked today and this week, and recent decisions.
- **History:** every screened call with the decision and the exact rule that matched,
  so you can always see why a call was or wasn't blocked. Filter by decision.
- A quiet daily summary notification of blocked calls.
- Dark theme and Material You dynamic colour.

### Privacy
- The only network access is downloading the list URLs you add.
- No analytics, crash reporting or ads.
- Android backup is disabled, so call history isn't copied to the cloud.

### Limitations
- Android 13+ only, and no iOS support yet.
- SMS spam isn't filtered.
- Phone makers that aggressively kill background apps (Xiaomi, Oppo, Vivo, Realme,
  some Samsung) can delay list refreshes and after-call prompts. Screening itself
  still works, but set the app's battery usage to **Unrestricted** (and enable
  **Autostart** on Xiaomi) for everything else.
- Only one app can be the Caller ID & spam app. Enabling this one replaces
  Truecaller or similar in that role.

## Install on your phone (sideload)

There are no prebuilt releases yet, so you build the APK yourself (see
[Development environment](#development-environment)):

```sh
devenv shell
./gradlew assembleDebug    # → app/build/outputs/apk/debug/app-debug.apk
```

Then pick one of the following ways to get it onto the phone.

### Option A: adb over USB
1. On the phone, open **Settings → About phone** and tap **Build number** 7 times to
   enable Developer options.
2. In **Settings → System → Developer options**, turn on **USB debugging**.
3. Connect the phone with a data cable, choose **File transfer** if asked, and accept
   the "Allow USB debugging?" prompt.
4. Inside `devenv shell`:
   ```sh
   adb devices                # the phone should be listed as "device"
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

If the phone doesn't show up in `lsusb` at all, try another port (preferably a rear
one on a desktop) or cable, or use option B. On Linux, a `no permissions` error usually
means you need udev rules (`sudo dnf install android-udev-rules` on Fedora).

### Option B: adb over Wi-Fi (no cable)
The phone and computer must be on the same network.
1. Enable Developer options (step 1 above), then turn on **Wireless debugging**.
2. Tap **Pair device with pairing code** and, inside `devenv shell`, run:
   ```sh
   adb pair <ip>:<pairing-port>     # enter the 6-digit code
   adb connect <ip>:<port>          # the port shown on the Wireless debugging screen
   adb devices
   ```
   If the phone is listed twice, add `-s <ip>:<port>` to adb commands, or
   `export ANDROID_SERIAL=<ip>:<port>`.
3. `adb install -r app/build/outputs/apk/debug/app-debug.apk`

Wireless debugging turns off after a restart or a network change. Turn it back on and run
`adb connect` again. You don't need to pair again.

### Option C: copy the APK
1. Send `app-debug.apk` to the phone (cloud drive, messaging app, email to yourself).
2. Open it and allow your file manager or browser to **install unknown apps**.
3. Android 13+ blocks call-log and contacts access for apps installed this way.
   If onboarding can't grant those permissions, open **Settings → Apps → Spam
   Blocker → ⋮ → Allow restricted settings**, then grant them in the app.

### First launch
1. Open **Spam Blocker** and follow the onboarding.
2. Accept the **Caller ID & spam app** role, and grant contacts, call log and
   notification access.
3. Import previously blocked numbers if offered.
4. Optionally add spam list URLs under **Sources**.

To check it works, have someone whose number isn't in your contacts call you, then
look at **History**.

**Updating:** install a new build over the old one with `adb install -r` to keep your
rules and history. The debug build is signed with a key stored in `.android/` in this
project folder. If that key is lost, a new build won't install over the old one, and
you would have to uninstall, which erases your data. Keep a copy of that folder.

## Development environment

The toolchain (JDK 21, Android SDK 37, build-tools, emulator + system image) is
pinned with [devenv](https://devenv.sh) via `devenv.yaml` / `devenv.lock`. The SDK
comes from [android-nixpkgs](https://github.com/tadfisher/android-nixpkgs).

```sh
devenv shell              # or `direnv allow` to load it automatically
./gradlew assembleDebug   # build app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest   # instrumented tests, needs a device or emulator
devenv test               # same unit tests, run inside the pinned environment
```

To change SDK versions, edit `devenv.nix` and `compileSdk`/`buildToolsVersion` in
`app/build.gradle.kts` together.

### Emulator

```sh
avd-create                # once: creates the spamblocker-api37 AVD under .android/
avd-run                   # boot it (needs KVM)
fake-call 1401234567      # simulate an incoming call
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Contacts' calls never reach a screening app (SPEC §2.3), so test with numbers that
aren't saved contacts.

### Debugging on a device

```sh
adb logcat -s CallScreener RuleIndexHolder AndroidRuntime
```

Debug builds show screening decision times (p50/p95) on the Home screen.
