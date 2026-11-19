#!/usr/bin/env bash
set -euo pipefail

options=(--no-daemon --max-workers=1 --console=plain --info --stacktrace "$NATIVE_CACHE_OPTION")
tasks=(:klause:linuxX64Test :klause-cli:linkReleaseExecutableLinuxX64 :klause:linkLpProfileReleaseExecutableLinuxX64)
log=$(mktemp)
trap 'rm -f "$log"' EXIT

recovered_kotlin_metadata() {
  grep -Fq "A problem occurred configuring project ':buildSrc'." "$log" || return 1
  grep -Fq "Could not resolve all artifacts for configuration 'classpath'." "$log" || return 1
  grep -Fq 'Could not find org.jetbrains.kotlin:kotlin-gradle-plugins-bom:' "$log" || return 1
  # A task failure must never rerun compilation or tests.
  if grep -q '^> Task ' "$log"; then return 1; fi

  local urls url status
  mapfile -t urls < <(sed -nE 's@^Resource missing\. \[HTTP (GET|HEAD): (https://repo\.maven\.apache\.org/maven2/org/jetbrains/kotlin/[a-zA-Z0-9._/-]+\.pom)\]$@\2@p' "$log" | sort -u)
  ((${#urls[@]} > 0 && ${#urls[@]} <= 10)) || return 1
  for url in "${urls[@]}"; do
    status=$(curl --silent --show-error --location --head --output /dev/null --write-out '%{http_code}' \
      --connect-timeout 5 --max-time 10 "$url") || return 1
    [[ "$status" == 200 ]] || return 1
  done
}

if ./gradlew "${options[@]}" "${tasks[@]}" 2>&1 | tee "$log"; then
  :
else
  statuses=("${PIPESTATUS[@]}")
  ((statuses[0] != 0)) || exit "${statuses[1]}"
  recovered_kotlin_metadata || exit "${statuses[0]}"
  printf '\nKotlin metadata is available after a configuration-time miss; retrying once with refreshed dependencies.\n'
  # Gradle caches missing metadata per repository; discard that negative entry.
  ./gradlew "${options[@]}" --refresh-dependencies "${tasks[@]}"
fi

# Separate processes give every iteration fresh test fixtures and runtime state.
for ((iteration = 2; iteration <= NATIVE_TEST_REPEAT; iteration++)); do
  printf '\nNative test process %s of %s\n' "$iteration" "$NATIVE_TEST_REPEAT"
  ./gradlew "${options[@]}" :klause:linuxX64Test --rerun
done
