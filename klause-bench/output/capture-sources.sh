#!/usr/bin/env bash
# Captures original corpus archive members byte for byte and checks them against expected SHA-256 digests.
# Usage: capture-sources.sh <out-dir> < requests.tsv
# Each request line is `<https archive .tar.zst url><TAB><member path><TAB><expected sha256>`. Every archive is
# downloaded once; only the requested members are extracted. capture.tsv records each member as captured,
# mismatch or missing, and the exit status is nonzero unless all were captured. Needs curl, GNU tar and zstd.
set -euo pipefail

out="${1:?usage: capture-sources.sh <out-dir> < requests.tsv}"
mkdir -p "$out"
requests="$out/requests.tsv"
cat > "$requests"
printf 'archive\tmember\texpected\tactual\tstatus\n' > "$out/capture.tsv"

failed=0
index=0
while IFS= read -r url; do
  index=$((index + 1))
  case "$url" in https://*) ;; *) echo "refusing non-HTTPS archive: $url" >&2; exit 2 ;; esac
  archive="$out/archive-$index.tar.zst"
  curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
    --max-filesize $((256 * 1024 * 1024)) --connect-timeout 30 --max-time 1200 --output "$archive" "$url"
  printf '%s\t%s\n' "$url" "$(sha256sum "$archive" | cut -d' ' -f1)" >> "$out/archives.tsv"
  members="$out/archive-$index"
  mkdir -p "$members"
  awk -F'\t' -v url="$url" '$1 == url { print $2 }' "$requests" > "$members.list"
  # Missing members make tar exit nonzero; each one is reported below instead.
  tar --zstd -xf "$archive" -C "$members" --no-same-owner --no-same-permissions -T "$members.list" 2>/dev/null || true
  while IFS=$'\t' read -r _ member expected; do
    file="$members/$member"
    if [ -f "$file" ]; then
      actual="$(sha256sum "$file" | cut -d' ' -f1)"
      status=$([ "$actual" = "$expected" ] && echo captured || echo mismatch)
    else
      actual=""
      status=missing
    fi
    [ "$status" = captured ] || failed=1
    printf '%s\t%s\t%s\t%s\t%s\n' "$url" "$member" "$expected" "$actual" "$status" >> "$out/capture.tsv"
  done < <(awk -F'\t' -v url="$url" '$1 == url' "$requests")
  rm -f "$archive" "$members.list"
done < <(cut -f1 "$requests" | awk 'NF && !seen[$0]++')

exit "$failed"
