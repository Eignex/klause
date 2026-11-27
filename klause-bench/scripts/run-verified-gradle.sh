#!/usr/bin/env bash
set -euo pipefail

scripts=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
project=$(cd -- "$scripts/../.." && pwd)
properties="$project/gradle/wrapper/gradle-wrapper.properties"
retry_buildsrc_metadata=false
if [[ ${1:-} == --retry-buildsrc-metadata ]]; then
    retry_buildsrc_metadata=true
    shift
fi
if ! grep -Fxq 'distributionUrl=https\://services.gradle.org/distributions/gradle-9.7.1-bin.zip' "$properties"; then
    printf 'The bootstrap Gradle asset must match the wrapper distribution.\n' >&2
    exit 1
fi
checksum=$(sed -n 's/^distributionSha256Sum=//p' "$properties")
[[ "$checksum" =~ ^[a-f0-9]{64}$ ]]

umask 077
stage=$(mktemp -d "${TMPDIR:-/tmp}/klause-bootstrap.XXXXXX")
trap 'rm -rf -- "$stage"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

gh api -H 'Accept: application/octet-stream' \
    repos/gradle/gradle-distributions/releases/assets/520945879 > "$stage/gradle.zip"
actual=$(sha256sum "$stage/gradle.zip")
if [[ "${actual%% *}" != "$checksum" ]]; then
    printf 'Gradle distribution checksum mismatch.\n' >&2
    exit 1
fi
printf 'Verified Gradle 9.7.1 from the release-asset API.\n'
jar_command=jar
if [[ -n ${JAVA_HOME:-} ]]; then
    jar_command="$JAVA_HOME/bin/jar"
fi
(cd -- "$stage" && "$jar_command" -xf gradle.zip)

bash "$scripts/stage-lab-sdk.sh" "$stage/sdk"
export ORG_GRADLE_PROJECT_labBenchRepository="file://$stage/sdk"
gradle=(bash "$stage/gradle-9.7.1/bin/gradle" "$@" --no-daemon)
if "$retry_buildsrc_metadata"; then
    bash "$scripts/run-gradle-with-metadata-recovery.sh" "${gradle[@]}"
else
    "${gradle[@]}"
fi
