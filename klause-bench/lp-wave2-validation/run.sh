#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
manifest="$root/klause-bench/lp-wave2-validation-manifest.json"
output=${1:-/home/rasmus/Workspaces/lp-evidence/session-2.4/run}
campaign_candidate_revision=a1e51591f4dcefd66682ba5128388b3a34ebcb80
historical_revision=cd66668668851eea37350db5af0d416fb27a7a0e

current_revision=$(git -C "$root" rev-parse HEAD)
if [[ "$current_revision" != "$campaign_candidate_revision" ]]; then
    printf '%s\n' \
        "LP Wave 2.4 evidence is frozen at candidate $campaign_candidate_revision." \
        "Create a detached worktree at that revision and run its copy of this script;" \
        "the campaign-era Koblas pin is incompatible with later source APIs." >&2
    exit 2
fi

historical=$(mktemp -d /tmp/klause-lp-wave2-historical-XXXXXX)
cleanup() {
    git -C "$root" worktree remove --force "$historical" >/dev/null 2>&1 || true
}
trap cleanup EXIT

mkdir -p "$output"
: > "$output/historical.jsonl"
: > "$output/candidate.jsonl"
: > "$output/arm-failures.log"
sha256sum "$manifest" > "$output/inputs.sha256"
sha256sum \
    "$root/klause/src/jvmTest/kotlin/com/eignex/klause/lp/LpWave2Acceptance.kt" \
    "$root/klause/src/lpWave2Historical/kotlin/com/eignex/klause/lp/LpWave2HistoricalAcceptance.kt" \
    "$root/klause-bench/lp-wave2-validation/historical.init.gradle" \
    "$root/klause-bench/lp-wave2-validation/frozen-koblas.init.gradle" \
    "$root/klause-bench/lp-wave2-validation/historical-compile.patch" \
    "$root/klause-bench/lp-wave2-validation/run.sh" >> "$output/inputs.sha256"

git -C "$root" worktree add --detach "$historical" "$historical_revision"
git -C "$historical" apply "$root/klause-bench/lp-wave2-validation/historical-compile.patch"

historical_run() {
    if ! timeout 60s "$historical/gradlew" -p "$historical" \
        -I "$root/klause-bench/lp-wave2-validation/frozen-koblas.init.gradle" \
        -I "$root/klause-bench/lp-wave2-validation/historical.init.gradle" \
        -DlpWave2.harnessRoot="$root" :klause:lpWave2HistoricalAcceptance \
        --args="measure repetition=$1 output=$output/historical.jsonl manifest=$manifest" --max-workers=2; then
        echo "arm=historical repetition=$1 timeoutOrFailure=true" >> "$output/arm-failures.log"
    fi
}

candidate_run() {
    if ! timeout 60s "$root/gradlew" -p "$root" \
        -I "$root/klause-bench/lp-wave2-validation/frozen-koblas.init.gradle" :klause:lpWave2Acceptance \
        --args="measure repetition=$1 output=$output/candidate.jsonl manifest=$manifest" --max-workers=2; then
        echo "arm=candidate repetition=$1 timeoutOrFailure=true" >> "$output/arm-failures.log"
    fi
}

historical_run warmup > "$output/historical-warmup.log" 2>&1
candidate_run warmup > "$output/candidate-warmup.log" 2>&1
for repetition in 0 1 2; do
    if (( repetition % 2 == 0 )); then
        historical_run "$repetition" > "$output/historical-$repetition.log" 2>&1
        candidate_run "$repetition" > "$output/candidate-$repetition.log" 2>&1
    else
        candidate_run "$repetition" > "$output/candidate-$repetition.log" 2>&1
        historical_run "$repetition" > "$output/historical-$repetition.log" 2>&1
    fi
done

set +e
"$root/gradlew" -p "$root" -I "$root/klause-bench/lp-wave2-validation/frozen-koblas.init.gradle" \
    :klause:lpWave2Acceptance \
    --args="compare historical=$output/historical.jsonl candidate=$output/candidate.jsonl summary=$output/summary.json manifest=$manifest armFailures=$output/arm-failures.log" \
    --max-workers=2 | tee "$output/comparison.log"
comparison_status=${PIPESTATUS[0]}
set -e
exit "$comparison_status"
