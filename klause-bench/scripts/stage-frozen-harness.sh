#!/usr/bin/env bash
set -euo pipefail

mode=${1:?mode required}
workspace=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)
destination=$workspace/klause-bench/build/frozen-harness
cli=$workspace/klause-cli/build/install/klause-cli-jvm

check_path() {
    local current=$1
    while [[ $current != "$workspace" ]]; do
        [[ $current == "$workspace/"* && ! -L $current ]] || {
            echo "Invalid or symlinked build path: $current" >&2
            exit 2
        }
        current=$(dirname "$current")
    done
}

check_tree() {
    check_path "$1"
    [[ -d $1 && -z $(find "$1" ! -type f ! -type d -print -quit) ]] || {
        echo "Expected a directory of regular files: $1" >&2
        exit 2
    }
}

inventory() {
    (cd "$1" && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum)
}

check_path "$destination"
if [[ -e $destination ]]; then check_tree "$destination"; fi
if [[ $mode == invalidate ]]; then
    [[ $# == 1 ]]
    rm -f -- "$destination/complete.sha256"
    exit 0
fi
[[ $mode == publish ]]
revision=${2:?revision required}
source=${3:?source required}
validation=${4:?validation required}
shift 4
[[ $revision =~ ^[a-f0-9]{40}$ ]]
[[ $source == "$workspace/klause-bench/build/frozen-harness-source/$revision" ]]
[[ $validation == "$workspace/klause-bench/build/frozen-harness-validation" ]]
check_path "$source"
check_tree "$source/klause-bench/build/install/klause-bench"
check_tree "$cli"
check_tree "$validation"
[[ -x $cli/bin/klause-cli ]]
[[ -f $source/klause-bench/build/install/klause-bench/lib/klause-bench-SNAPSHOT.jar ]]
git=(env -u GIT_DIR -u GIT_WORK_TREE -u GIT_COMMON_DIR -u GIT_INDEX_FILE
    -u GIT_OBJECT_DIRECTORY -u GIT_ALTERNATE_OBJECT_DIRECTORIES -u GIT_SHALLOW_FILE
    -u GIT_CONFIG -u GIT_CONFIG_COUNT -u GIT_CONFIG_PARAMETERS
    GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_NO_REPLACE_OBJECTS=1
    git -c core.hooksPath=/dev/null)
[[ $("${git[@]}" -C "$source" rev-parse HEAD) == "$revision" ]]
[[ -z $("${git[@]}" -C "$source" status --porcelain --untracked-files=normal) ]]
[[ -z $("${git[@]}" -C "$workspace" status --porcelain --untracked-files=all -- klause klause-cli) ]]
grep -Fx -- "revision=$revision" "$validation/verification.properties" > /dev/null
(cd "$source/klause-bench/build/install/klause-bench" && sha256sum --check --strict "$validation/artifacts.sha256")

temporary=$(mktemp -d "$workspace/klause-bench/build/.frozen-stage-XXXXXXXX")
trap 'rm -rf -- "$temporary"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir "$temporary/next"
cp -R -- "$source/klause-bench/build/install/klause-bench" "$temporary/next/distribution"
cp -R -- "$validation" "$temporary/next/harness-validation"
(cd "$temporary/next/distribution" && sha256sum --check --strict ../harness-validation/artifacts.sha256)
inventory "$temporary/next/harness-validation" > "$temporary/next/harness-validation.sha256"
inventory "$temporary/next/distribution" > "$temporary/next/distribution.sha256"
inventory "$cli" > "$temporary/next/cli.sha256"
(
    cd "$workspace"
    "${git[@]}" ls-files --cached --others --exclude-standard -z -- '*.gradle.kts' gradle.properties gradlew gradlew.bat gradle buildSrc |
        LC_ALL=C sort -z | xargs -0 sha256sum
    sha256sum klause-bench/scripts/prepare-frozen-harness.sh klause-bench/scripts/stage-frozen-harness.sh
) > "$temporary/next/build-inputs.sha256"
for init_script in "$@"; do
    [[ -f $init_script ]]
    sha256sum -- "$init_script" >> "$temporary/next/build-inputs.sha256"
done
{
    printf 'version=3\nharnessRevision=%s\n' "$revision"
    printf 'workspaceRevision=%s\n' "$("${git[@]}" -C "$workspace" rev-parse HEAD)"
    printf 'solverTree=%s\n' "$("${git[@]}" -C "$workspace" rev-parse HEAD:klause)"
    printf 'cliTree=%s\n' "$("${git[@]}" -C "$workspace" rev-parse HEAD:klause-cli)"
    printf 'distribution=klause-bench/build/frozen-harness/distribution\n'
    printf 'cliDistribution=klause-cli/build/install/klause-cli-jvm\n'
    printf 'cliLauncher=klause-cli/build/install/klause-cli-jvm/bin/klause-cli\n'
    printf 'initScriptCount=%s\n' "$#"
} > "$temporary/next/provenance.properties"
(
    cd "$temporary/next"
    sha256sum distribution.sha256 cli.sha256 build-inputs.sha256 provenance.properties harness-validation.sha256 > complete.sha256
    sha256sum --check --strict complete.sha256
    (cd distribution && sha256sum --check --strict ../distribution.sha256)
    (cd harness-validation && sha256sum --check --strict ../harness-validation.sha256)
)
(cd "$cli" && sha256sum --check --strict "$temporary/next/cli.sha256")
if [[ -e $destination ]]; then mv -- "$destination" "$temporary/previous"; fi
mv -- "$temporary/next" "$destination"
printf 'Frozen harness stage complete: %s\n' "$destination"
cat "$destination/complete.sha256"
