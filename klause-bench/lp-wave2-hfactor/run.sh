#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
manifest="$root/klause-bench/lp-wave2-hfactor-manifest.json"
corpus="$root/klause/src/jvmTest/resources/basis-corpus"
output=${1:-/home/rasmus/Workspaces/lp-evidence/session-2.4-hfactor}

if [[ -n "$(git -C "$root" status --porcelain)" ]]; then
    echo "refusing to benchmark a dirty checkout" >&2
    exit 2
fi
if [[ -e "$output" ]]; then
    echo "refusing to overwrite existing campaign evidence at $output" >&2
    exit 2
fi

mkdir -p "$(dirname "$output")"
mkdir "$output"
git -C "$root" rev-parse HEAD > "$output/source-revision.txt"
git -C "$root" rev-parse 'HEAD^{tree}' > "$output/source-tree.txt"
sha256sum \
    "$manifest" \
    "$root/klause-bench/lp-wave2-hfactor/run.sh" \
    "$corpus/manifest.json" \
    "$corpus"/*.kbtrace > "$output/inputs.sha256"

set +e
timeout 300s "$root/gradlew" -p "$root" \
    --refresh-dependencies \
    :klause:dependencyInsight \
    --configuration jvmTestRuntimeClasspath \
    --dependency com.eignex:koblas \
    :klause:basisTrace \
    --args="benchmark" \
    --max-workers=2 \
    --console=plain 2>&1 | tee "$output/campaign.log"
benchmark_status=${PIPESTATUS[0]}
set -e
if [[ "$benchmark_status" -ne 0 ]]; then
    exit "$benchmark_status"
fi

grep -E '^com\.eignex:(koblas|koblas-jvm|koblas-hfactor):.*:[0-9]{8}\.' \
    "$output/campaign.log" > "$output/resolved-dependencies.txt"
grep -q '^com\.eignex:koblas:' "$output/resolved-dependencies.txt"
grep -q '^com\.eignex:koblas-jvm:' "$output/resolved-dependencies.txt"
grep -q '^com\.eignex:koblas-hfactor:' "$output/resolved-dependencies.txt"
grep -E '^\{.*\}$' "$output/campaign.log" > "$output/benchmark.jsonl"
jq -e -s --slurpfile corpus "$corpus/manifest.json" \
    'length == 12 and all(.[];
        .command == "benchmark" and
        .repetitions == 3 and
        .validRepetitions == 3 and
        .allRepetitionsValid == true and
        .stateErrors == 0) and
     ([.[].id] | unique | length) == 6 and
     ([.[].id] | unique | sort) == ([$corpus[0].fixtures[].id] | sort) and
     ([.[].backend] | unique | sort) == ["custom", "hfactor"] and
     ([group_by([.id, .backend])[] | length] | length == 12 and all(.[]; . == 1)) and
     all(.[]; . as $record |
         any($corpus[0].fixtures[];
             .id == $record.id and
             .format == $record.format and
             .route == $record.route and
             .sourceSha256 == $record.sourceSha256 and
             .artifactSha256 == $record.artifactSha256)) and
     ([.[].koblasArtifactSha256] | unique | length) == 1 and
     ([.[].hfactorArtifactSha256] | unique | length) == 1 and
     (.[0].koblasArtifactSha256 | test("^[0-9a-f]{64}$")) and
     (.[0].hfactorArtifactSha256 | test("^[0-9a-f]{64}$"))' \
    "$output/benchmark.jsonl" > "$output/valid.txt"

jq -s \
    'def median($name): .[($name + "Median")];
     def production($backend): map(select(.route == "PRODUCTION_RELAXATION" and .backend == $backend));
     . as $records |
     {
       schemaVersion: 1,
       records: length,
       traces: ([.[].id] | unique | length),
       allValid: all(.[]; .allRepetitionsValid and .stateErrors == 0),
       koblasArtifactSha256: .[0].koblasArtifactSha256,
       hfactorArtifactSha256: .[0].hfactorArtifactSha256,
       productionMedianSums: {
         customTotalNanos: (production("custom") | map(median("totalNanos")) | add),
         hfactorTotalNanos: (production("hfactor") | map(median("totalNanos")) | add),
         customComposedNanos: (production("custom") | map(median("composedNanos")) | add),
         hfactorComposedNanos: (production("hfactor") | map(median("composedNanos")) | add)
       },
       traceRatios: [
         ([.[].id] | unique[]) as $id |
         ($records[] | select(.id == $id and .backend == "custom")) as $custom |
         ($records[] | select(.id == $id and .backend == "hfactor")) as $hfactor |
         {
           id: $id,
           route: $custom.route,
           totalCustomToHfactor: ($custom.totalNanosMedian / $hfactor.totalNanosMedian),
           composedCustomToHfactor: ($custom.composedNanosMedian / $hfactor.composedNanosMedian)
         }
       ]
     } |
     .productionMedianSums as $s |
     .productionTotalCustomToHfactor = ($s.customTotalNanos / $s.hfactorTotalNanos) |
     .productionComposedCustomToHfactor = ($s.customComposedNanos / $s.hfactorComposedNanos)' \
    "$output/benchmark.jsonl" > "$output/summary.json"
