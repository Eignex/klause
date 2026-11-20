#!/usr/bin/env bash
set -euo pipefail

revision=${1:?revision required}
destination=${2:?destination required}
[[ $revision =~ ^[a-f0-9]{40}$ ]] || { echo 'Expected an exact commit SHA' >&2; exit 2; }
workspace=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)
git=(env -u GIT_DIR -u GIT_WORK_TREE -u GIT_COMMON_DIR -u GIT_INDEX_FILE
    -u GIT_OBJECT_DIRECTORY -u GIT_ALTERNATE_OBJECT_DIRECTORIES -u GIT_SHALLOW_FILE
    -u GIT_CONFIG -u GIT_CONFIG_COUNT -u GIT_CONFIG_PARAMETERS
    GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_NO_REPLACE_OBJECTS=1
    git -c core.hooksPath=/dev/null -c protocol.allow=never -c protocol.file.allow=always)
[[ $destination == "$workspace/klause-bench/build/frozen-harness-source/$revision" ]] || {
    echo 'Frozen harness checkout must stay in its build directory' >&2
    exit 2
}
for owned_path in "$workspace/klause-bench" "$workspace/klause-bench/build" \
    "$workspace/klause-bench/build/frozen-harness-source" "$destination"; do
    [[ ! -L $owned_path ]] || { echo "Symlinked build path: $owned_path" >&2; exit 2; }
done
"${git[@]}" -C "$workspace" cat-file -e "$revision^{commit}"
"${git[@]}" -C "$workspace" merge-base --is-ancestor "$revision" HEAD
if [[ -e $destination ]]; then
    [[ -d $destination/.git && ! -L $destination/.git ]]
    [[ $("${git[@]}" -C "$destination" config --local --get core.symlinks) == false ]]
    [[ -z $(find "$destination" -type l -print -quit) ]]
    [[ ! -e $destination/.git/objects/info/alternates ]]
    [[ -z $(find "$destination/.git" -type l -print -quit) ]]
    [[ -z $(find "$destination/.git/objects" -type f -links +1 -print -quit) ]]
    [[ $("${git[@]}" -C "$destination" rev-parse HEAD) == "$revision" ]]
    [[ -z $("${git[@]}" -C "$destination" status --porcelain --untracked-files=normal) ]]
    cmp "$workspace/gradle/wrapper/gradle-wrapper.properties" \
        "$destination/gradle/wrapper/gradle-wrapper.properties"
    exit 0
fi

mkdir -p "$(dirname "$destination")"
temporary=$(mktemp -d "$(dirname "$destination")/.prepare-XXXXXXXX")
trap 'rm -rf -- "$temporary"' EXIT
"${git[@]}" init --quiet --template= "$temporary/source"
"${git[@]}" -C "$temporary/source" config --local core.symlinks false
"${git[@]}" -C "$temporary/source" fetch --quiet --depth=1 --no-tags -- "$workspace" "$revision"
"${git[@]}" -C "$temporary/source" checkout --quiet --detach "$revision"
cmp "$workspace/gradle/wrapper/gradle-wrapper.properties" \
    "$temporary/source/gradle/wrapper/gradle-wrapper.properties"
mv "$temporary/source" "$destination"
