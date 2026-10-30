#!/usr/bin/env bash
set -euo pipefail

[[ $# -eq 3 ]] || { echo 'usage: summarize-jfr.sh SOURCE SHA256 GZIP_BASE64_FILE' >&2; exit 2; }
capture_source=$1
capture_sha=$2
capture_encoded=$3
[[ $capture_sha =~ ^[a-f0-9]{64}$ ]] || { echo 'invalid capture SHA-256' >&2; exit 2; }
capture_out=${OUT:?OUT must name the lab output directory}
mkdir -p "$capture_out"
ulimit -f 524288
ulimit -t 90
[[ $(wc -c < "$capture_encoded") -le 2097152 ]] || { echo 'encoded capture exceeds 2 MiB' >&2; exit 2; }
base64 --decode "$capture_encoded" | gzip --decompress > "$capture_out/capture.jfr"
[[ $(wc -c < "$capture_out/capture.jfr") -le 16777216 ]] || { echo 'capture exceeds 16 MiB' >&2; exit 2; }
printf '%s  %s\n' "$capture_sha" "$capture_out/capture.jfr" | sha256sum --check
jfr print --json --events jdk.ExecutionSample --stack-depth 32 "$capture_out/capture.jfr" > "$capture_out/samples.json"
jq --arg source "$capture_source" --arg sha256 "$capture_sha" '
  def method: .method | (.type.name | gsub("/"; ".")) + "." + .name;
  .recording.events as $samples |
  {
    source: $source, sha256: $sha256, samples: ($samples | length),
    stackDepth: 32,
    truncatedStacks: ([$samples[] | select(.values.stackTrace.truncated == true)] | length),
    sampledMethods: (
      [$samples[] | .values.stackTrace.frames // [] | map(method) | unique | .[]] |
      group_by(.) | map({method: .[0], samples: length}) | sort_by(-.samples, .method)
    )
  }
' "$capture_out/samples.json" > "$capture_out/summary.json"
