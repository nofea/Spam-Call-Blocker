# Spam-Call-Blocker
Personal Android (Kotlin) app that screens and blocks Indian spam calls. See the [specification](docs/SPEC.md).

## Development environment

The toolchain (JDK 21, Android SDK 37, build-tools, emulator + system image) is
pinned with [devenv](https://devenv.sh) via `devenv.yaml` / `devenv.lock`. The SDK
comes from [android-nixpkgs](https://github.com/tadfisher/android-nixpkgs).

```sh
devenv shell              # or `direnv allow` to load it automatically
./gradlew assembleDebug   # build app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest
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
