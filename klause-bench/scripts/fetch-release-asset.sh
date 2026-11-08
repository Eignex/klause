#!/usr/bin/env bash
set -euo pipefail

repository=${1:?GitHub repository is required}
asset=${2:?Release asset ID is required}
destination=${3:?Destination is required}
endpoint="https://api.github.com/repos/$repository/releases/assets/$asset"

if command -v gh >/dev/null 2>&1; then
    gh api -H 'Accept: application/octet-stream' "repos/$repository/releases/assets/$asset" > "$destination"
elif command -v curl >/dev/null 2>&1; then
    curl --fail --location --connect-timeout 10 --max-time 60 --retry 2 --retry-max-time 120 \
        --proto '=https' --proto-redir '=https' -H 'Accept: application/octet-stream' \
        --output "$destination" "$endpoint"
elif command -v wget >/dev/null 2>&1; then
    wget --timeout=30 --tries=3 --header='Accept: application/octet-stream' \
        --output-document="$destination" "$endpoint"
else
    printf 'A GitHub release download requires gh, curl or wget.\n' >&2
    exit 1
fi
