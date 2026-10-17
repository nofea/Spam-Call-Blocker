{ pkgs, inputs, ... }:

let
  # Keep in sync with compileSdk / buildToolsVersion in app/build.gradle.kts.
  # The SDK is composed from android-nixpkgs (see devenv.yaml) rather than
  # devenv's `android` module so that the emulator system image lives in the
  # same, read-only SDK root as everything else.
  androidSdk = inputs.android-nixpkgs.sdk.${pkgs.stdenv.system} (sdk: with sdk; [
    cmdline-tools-latest
    platform-tools
    build-tools-37-0-0
    platforms-android-37-0
    emulator
    # Emulator image for instrumented tests and simulated incoming calls
    # (`adb emu gsm call <number>`).
    system-images-android-37-0-google-apis-x86-64
  ]);
  androidHome = "${androidSdk}/share/android-sdk";
  buildToolsVersion = "37.0.0";
  avdName = "spamblocker-api37";
in
{
  languages.java = {
    enable = true;
    jdk.package = pkgs.jdk21;
  };

  packages = [ androidSdk pkgs.git ];

  env.ANDROID_HOME = androidHome;
  env.ANDROID_SDK_ROOT = androidHome;
  # Use the SDK's patched aapt2 instead of the one Gradle downloads from Maven,
  # which doesn't run on NixOS.
  env.GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidHome}/build-tools/${buildToolsVersion}/aapt2";

  enterShell = ''
    # Keep AVDs and adb keys inside the project instead of ~/.android.
    export ANDROID_USER_HOME="$DEVENV_ROOT/.android"
    export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
    mkdir -p "$ANDROID_AVD_HOME"

    printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$DEVENV_ROOT/local.properties"
  '';

  scripts.avd-create = {
    description = "Create the ${avdName} emulator (API 37, x86_64)";
    exec = ''
      echo no | avdmanager create avd --force --name ${avdName} \
        --package 'system-images;android-37.0;google_apis;x86_64' --device pixel_8
    '';
  };

  scripts.avd-run = {
    description = "Boot the ${avdName} emulator";
    exec = ''emulator -avd ${avdName} "$@"'';
  };

  scripts.fake-call = {
    description = "Simulate an incoming call on the running emulator: fake-call <number>";
    exec = ''adb emu gsm call "''${1:?usage: fake-call <number>}"'';
  };

  enterTest = ''
    ./gradlew --no-daemon testDebugUnitTest
  '';
}
