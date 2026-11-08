#!/usr/bin/env bash
set -euo pipefail

sdk_root=${1:?SDK repository directory is required}
sdk_version=0.1.0
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
53247d50debd77f6c1d7fa314ffa5ddb6c0fe0632c2d482b01c7704afe7beba4  lab-bench-0.1.0.jar
2394bfcd15474a473cdf34827b99cb4a03dc3bdb270ebd705ef2dd2c8298a489  lab-bench-0.1.0.module
a25c59d8bd1520368789459fd42a64bb1a5c376762ea86ca1de6b86e4133f160  lab-bench-0.1.0.pom
CHECKSUMS
)
mv -T -- "$sdk_stage" "$sdk_root/lab-bench-v$sdk_version"
