# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Personal Android (Kotlin) app that screens incoming calls and blocks Indian spam calls. Sideloaded, not for Google Play. **`docs/SPEC.md` is the source of truth** for behaviour, data model, defaults and package layout. Code comments cite it by section (e.g. `// SPEC §3`), so keep doing that, and update the spec when behaviour changes.

## Environment & commands

The toolchain (JDK 21, Android SDK/build-tools 37, emulator + API 37 image) is pinned with devenv (`devenv.nix`, `devenv.yaml`, `devenv.lock`; the SDK comes from android-nixpkgs). Run every build command inside `devenv shell`, or through direnv via `.envrc`. The shell sets `ANDROID_HOME`, writes `local.properties`, keeps AVDs and adb keys in `.android/` inside the project, and passes `GRADLE_OPTS` so Gradle uses the SDK's patched `aapt2` (the one Gradle downloads from Maven doesn't run on NixOS).

```sh
./gradlew assembleDebug                      # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest                  # JVM unit tests
./gradlew testDebugUnitTest --tests 'com.sheen.spamblocker.phone.PhoneNormalizerTest'
./gradlew testDebugUnitTest --tests '*PhoneNormalizerTest.TRAI*'   # single test (backtick names)
./gradlew connectedDebugAndroidTest          # instrumented tests (needs a running emulator; avd-run -no-window works headless)
devenv test                                  # unit tests inside the pinned env
```

devenv doesn't provide `gradle`; always use `./gradlew` (Gradle 9.8.1, pinned with a distribution checksum). Release builds are minified with R8. They're signed when the `spamblocker.keystore`, `spamblocker.keystorePassword`, `spamblocker.keyAlias` and `spamblocker.keyPassword` Gradle properties are set (in `~/.gradle/gradle.properties`, never in the repo), and unsigned otherwise.

Dependencies are checked against SHA-256 hashes in `gradle/verification-metadata.xml`, and a mismatch fails the build. After changing any version in `gradle/libs.versions.toml`, regenerate the file with `./gradlew --refresh-dependencies --write-verification-metadata sha256 assembleDebug assembleRelease testDebugUnitTest assembleDebugAndroidTest`, then review the diff: entries should only be added. A changed hash for a version that didn't change means something was tampered with. Never hand-edit hashes to make a build pass.

Emulator helpers (devenv scripts): `avd-create` (once), `avd-run` (needs KVM), `fake-call <number>` (wraps `adb emu gsm call`), then `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Calls from saved contacts never reach a screening app, so test with numbers that aren't saved contacts.

When changing SDK versions, update the SDK packages in `devenv.nix` and `compileSdk`/`buildToolsVersion` in `app/build.gradle.kts` together. Dependency versions live in `gradle/libs.versions.toml`.

## Architecture

Single `:app` module, package `com.sheen.spamblocker`, MVVM with Hilt, Room (schemas exported to `app/schemas/`), DataStore, WorkManager, OkHttp, kotlinx.serialization, libphonenumber, Compose + Material 3. Layout follows SPEC §12, plus `di/AppModule` (singletons and the `@AppScope` coroutine scope) and `ui/common`. `SpamBlockerApp.onCreate` starts the rule index, notification channels, periodic jobs, the post-call observer, and the debounced re-derivation of suggestions.

How a call is screened (SPEC §5, §10):
- `screening/SpamScreeningService` (a `CallScreeningService` that holds `ROLE_CALL_SCREENING`) only maps `Call.Details` in and a `CallResponse` out. `screening/CallScreener` does the work: normalize, get the index, run `DecisionEngine`, then record the `CallEvent` and notify in the background. Tests drive `CallScreener` directly, because `Call.Details` can't be constructed. It has to respond quickly: the target is p95 under 100 ms, and the system timeout is about 5 s. Outgoing calls are allowed immediately.
- `DecisionEngine` runs an ordered pipeline where the **first match wins** and allow stages (contacts, allowlist, TRAI 160, recent outgoing, repeat caller) always come before block stages (exact list, wildcards, derived prefixes, remote lists, TRAI 140, hidden, international). Hidden callers skip straight to the hidden-caller stage. Every decision is written to `CallEvent` with the stage and rule ID that matched, plus `decisionMicros`.
- `RuleIndex` is immutable: exact numbers live in sorted primitive arrays (binary search, a few MB for 200k entries), prefix and `?` patterns live in an array-backed digit trie, and suffixes are a linear list. `data/RuleIndexHolder` keeps it in an `AtomicReference`, rebuilds it from Room invalidations of the rule tables (debounced by 500 ms), and swaps it atomically. `onScreenCall` must never wait for a rebuild. Block-rule stage comes from `RuleIndexHolder.stageFor`.
- The Android-facing lookups (contacts, call log, recent events) are interfaces handed to the engine, so they can be faked in tests.

Invariants:
- **Pure-JVM core**: `DecisionEngine`, `RuleIndex`, `WildcardCompiler`, `PrefixDeriver`, `PhoneNormalizer` and the list parsers must not use Android framework types. They're tested in `app/src/test` with JUnit 4.
- **Numbers**: everything is stored and compared in E.164 form via `phone/PhoneNormalizer` (region `IN`), which returns a `NormalizedNumber` (`Hidden` / `Parsed` / `Unparsed`). TRAI series checks use the *national significant number*, and the prefix sets live only in `rules/TraiSeries`.
- **Privacy**: no analytics, crash-reporting or ad SDKs. The only network requests allowed are fetches of the user's configured list URLs. Backup is disabled (`allowBackup=false`, `data_extraction_rules.xml`), so keep it that way.
- Don't use `setSkipCallLog`. The system call log is what the app learns from.
- Derived prefix suggestions never take effect without the user accepting them.
- WorkManager's default initializer is removed in the manifest, and `SpamBlockerApp` provides `HiltWorkerFactory`. Workers should be `@HiltWorker`.
