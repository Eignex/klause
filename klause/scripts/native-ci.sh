#!/usr/bin/env bash
set -euo pipefail

options=(--no-daemon --max-workers=1 --console=plain --info --stacktrace "$NATIVE_CACHE_OPTION")
./gradlew "${options[@]}" --refresh-dependencies :klause:linuxX64Test :klause-cli:linkReleaseExecutableLinuxX64 :klause:linkLpProfileReleaseExecutableLinuxX64

# Separate processes give every iteration fresh test fixtures and runtime state.
for ((iteration = 2; iteration <= NATIVE_TEST_REPEAT; iteration++)); do
  printf '\nNative test process %s of %s\n' "$iteration" "$NATIVE_TEST_REPEAT"
  ./gradlew "${options[@]}" :klause:linuxX64Test --rerun
done
