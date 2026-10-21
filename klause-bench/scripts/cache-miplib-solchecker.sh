#!/usr/bin/env bash
set -euo pipefail

archive="${1:?archive output path required}"
upstream="https://miplib.zib.de/downloads/miplib2017-testscript-v1.0.4.zip"
cache="https://api.github.com/repos/Eignex/klause/releases/assets/662279198"
sha256="37b21a7f1ee968aed60675fb070c9286eccc6a953c8bc50120c9246b052b47b9"

mkdir -p "$(dirname "$archive")"
if ! curl --fail --silent --show-error --location --connect-timeout 10 --max-time 30 \
    "$upstream" --output "$archive"; then
    curl --fail --silent --show-error --location --connect-timeout 10 --max-time 60 \
        --header "Authorization: Bearer ${GH_TOKEN:?GitHub token required for the unpublished CI cache}" \
        --header "Accept: application/octet-stream" "$cache" --output "$archive"
fi
printf '%s  %s\n' "$sha256" "$archive" | sha256sum --check --
