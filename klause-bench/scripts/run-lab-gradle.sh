#!/usr/bin/env bash
set -euo pipefail

scripts=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
project=$(cd -- "$scripts/../.." && pwd)
properties="$project/gradle/wrapper/gradle-wrapper.properties"
if ! grep -Fxq 'distributionUrl=https\://services.gradle.org/distributions/gradle-9.7.1-bin.zip' "$properties"; then
    printf 'The lab Gradle asset must match the wrapper distribution.\n' >&2
    exit 1
fi
checksum=$(sed -n 's/^distributionSha256Sum=//p' "$properties")
[[ "$checksum" =~ ^[a-f0-9]{64}$ ]]
stage=$(mktemp -d "${TMPDIR:-/tmp}/klause-gradle.XXXXXX")
trap 'rm -rf -- "$stage"' EXIT

bash "$scripts/fetch-release-asset.sh" gradle/gradle-distributions 520945879 "$stage/gradle.zip"
printf '%s  %s\n' "$checksum" "$stage/gradle.zip" | sha256sum --check
jar_command=jar
if [[ -n ${JAVA_HOME:-} ]]; then
    jar_command="$JAVA_HOME/bin/jar"
fi
(cd -- "$stage" && "$jar_command" -xf gradle.zip)
if [[ -z ${ORG_GRADLE_PROJECT_labBenchRepository:-} ]]; then
    bash "$scripts/stage-lab-sdk.sh" "$stage/sdk"
    export ORG_GRADLE_PROJECT_labBenchRepository="file://$stage/sdk"
fi
bash "$stage/gradle-9.7.1/bin/gradle" "$@"
