#!/usr/bin/env bash
set -euo pipefail

sdk_root=${1:?SDK repository directory is required}
sdk_version=0.1.1
mkdir -p "$sdk_root"
sdk_stage=$(mktemp -d "$sdk_root/.lab-bench.XXXXXX")
trap 'rm -rf -- "$sdk_stage"' EXIT

release=$(gh api "repos/Eignex/eignex-lab/releases/tags/lab-bench-v$sdk_version")
for extension in module jar pom; do
    artifact="lab-bench-$sdk_version.$extension"
    asset_id=$(jq -er --arg name "$artifact" '
        [.assets[] | select(.name == $name)] |
        if length == 1 then .[0].id else error("expected one release asset") end
    ' <<< "$release")
    gh api -H 'Accept: application/octet-stream' \
        "repos/Eignex/eignex-lab/releases/assets/$asset_id" > "$sdk_stage/$artifact"
done

(
    cd "$sdk_stage"
    sha256sum --check <<'CHECKSUMS'
1cc796aae19c6fa08d6ada58617c44f4c930345fa332b29c1c8f5f9ab250ef81  lab-bench-0.1.1.jar
08fd66134b98b65bdf407b05dfa5eb477a8116123755a88f8fc3a5661a3ed6bf  lab-bench-0.1.1.module
473b274356034bcb4ea5cf9a5b21f933c36b1da0ffb2a388e1dd494fc322ce95  lab-bench-0.1.1.pom
CHECKSUMS
)
mv -T -- "$sdk_stage" "$sdk_root/lab-bench-v$sdk_version"
