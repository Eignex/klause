#!/usr/bin/env bash
set -euo pipefail

sdk_root=${1:?SDK repository directory is required}
sdk_version=0.1.1
umask 077
mkdir -p "$sdk_root"
sdk_stage=$(mktemp -d "$sdk_root/.lab-bench.XXXXXX")
trap 'rm -rf -- "$sdk_stage"' EXIT

while read -r extension asset_id; do
    gh api -H 'Accept: application/octet-stream' \
        "repos/Eignex/eignex-lab/releases/assets/$asset_id" > "$sdk_stage/lab-bench-$sdk_version.$extension"
done <<'ASSETS'
module 754307582
jar 754307577
pom 754307580
ASSETS

(
    cd "$sdk_stage"
    while read -r checksum artifact; do
        actual=$(sha256sum "$artifact")
        if [[ "${actual%% *}" != "$checksum" ]]; then
            printf 'Lab SDK checksum mismatch: %s\n' "$artifact" >&2
            exit 1
        fi
        printf 'Verified lab SDK %s from the release-asset API.\n' "$artifact"
    done <<'CHECKSUMS'
1cc796aae19c6fa08d6ada58617c44f4c930345fa332b29c1c8f5f9ab250ef81  lab-bench-0.1.1.jar
08fd66134b98b65bdf407b05dfa5eb477a8116123755a88f8fc3a5661a3ed6bf  lab-bench-0.1.1.module
473b274356034bcb4ea5cf9a5b21f933c36b1da0ffb2a388e1dd494fc322ce95  lab-bench-0.1.1.pom
CHECKSUMS
)
mv -T -- "$sdk_stage" "$sdk_root/lab-bench-v$sdk_version"
