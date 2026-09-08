#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd "$script_dir/../.." && pwd)
manifest="$repo_root/klause-bench/lp-wave0-manifest.json"
cache_home=$(getent passwd "$(id -u)" | cut -d: -f6)
corpus_root="${KLAUSE_BENCH_CORPUS_ROOT:-$cache_home/.cache/klause-bench/corpus}"

die() {
    printf 'lp-wave0-validation: %s\n' "$*" >&2
    exit 1
}

require_tools() {
    command -v jq >/dev/null || die "jq is required"
    command -v sha256sum >/dev/null || die "sha256sum is required"
}

verify_pairs() {
    local suite=$1
    local root=$2
    local suffix=$3
    local mode=$4
    while IFS=$'\t' read -r id expected; do
        local file
        if [[ "$mode" == "flat" ]]; then
            file="$root/$id.$suffix"
        else
            file=$(find "$root" -type f -path "*/$id.$suffix" -print -quit)
        fi
        [[ -n "$file" && -f "$file" ]] || die "$suite instance is absent: $id"
        local actual
        actual=$(sha256sum "$file" | cut -d' ' -f1)
        [[ "$actual" == "$expected" ]] || die "$suite hash mismatch for $id: $actual"
    done < <(
        jq -r --arg suite "$suite" \
            '.suiteSlices[] | select(.suite == $suite) | .instances[] | select(type == "array") | @tsv' \
            "$manifest"
    )
}

verify() {
    require_tools
    jq empty "$manifest"
    while IFS=$'\t' read -r file expected; do
        local actual
        actual=$(sha256sum "$repo_root/$file" | cut -d' ' -f1)
        [[ "$actual" == "$expected" ]] || die "reference table hash mismatch for $file: $actual"
    done < <(jq -r '.referenceTablesAtBase[] | [.file, .sha256] | @tsv' "$manifest")

    verify_pairs smtlib-qflra "$corpus_root/smtlib-qf_lra" smt2 nested
    verify_pairs smtlib-qflira "$corpus_root/smtlib-qf_lira" smt2 nested
    verify_pairs smtlib-qflia "$corpus_root/smtlib-qf_lia" smt2 nested
    verify_pairs smtlib-qfidl "$corpus_root/smtlib-qf_idl" smt2 nested
    verify_pairs smtlib-qfrdl "$corpus_root/smtlib-qf_rdl" smt2 nested
    verify_pairs miplib2017 "$corpus_root/miplib2017" mps flat
    verify_pairs xcsp3-core "$repo_root/klause-bench/smoke-corpus/xcsp3" xml flat
    verify_pairs mps-core "$repo_root/klause-bench/smoke-corpus/mps" mps flat

    local expected_mzn actual_mzn
    expected_mzn=$(jq -r '.suiteSlices[] | select(.suite == "mzn-bench") | .corpusCommit' "$manifest")
    actual_mzn=$(git -C "$corpus_root/mzn-challenge" rev-parse HEAD)
    [[ "$actual_mzn" == "$expected_mzn" ]] || die "mzn-challenge is $actual_mzn, expected $expected_mzn"
    printf 'manifest and frozen corpus inputs verified\n'
}

preview() {
    verify
    cd "$repo_root"
    for suite in smtlib-qflra smtlib-qflira smtlib-qflia smtlib-qfidl smtlib-qfrdl; do
        ./gradlew -q :klause-bench:bench \
            --args="preview suite=$suite per-family=1 max=10 seed=1"
    done
    ./gradlew -q :klause-bench:bench \
        --args="preview suite=mzn-bench per-family=1 max=60 seed=1"
    ./gradlew -q :klause-bench:bench --args="preview suite=xcsp3-core"
    ./gradlew -q :klause-bench:bench --args="preview suite=mps-core"
    ./gradlew -q :klause-bench:bench \
        --args="preview suite=miplib2017 name=10teams,22433,23588,2club200v15p5scn,30_70_45_05_100,30_70_45_095_100,30n20b8,50v-10,8div-n59k10,8div-n59k11,CMS750_4,Test3"
}

check() {
    verify
    cd "$repo_root"
    ./gradlew :klause:jvmTest \
        --tests com.eignex.klause.lp.engine.LpReferenceAdapterTest \
        --tests com.eignex.klause.lp.engine.LpReplayHarnessTest \
        --tests com.eignex.klause.lp.engine.LpInstrumentationHarness
}

instrument() {
    [[ "${KLAUSE_LP_IDLE_WINDOW:-}" == "1" ]] || \
        die "set KLAUSE_LP_IDLE_WINDOW=1 only after reserving an idle host window"
    verify
    cd "$repo_root"
    local head_sha result_dir log_file
    head_sha=$(git rev-parse HEAD)
    result_dir="$repo_root/klause-bench/output/lp-wave0-validation-$head_sha"
    [[ ! -e "$result_dir" ]] || die "refusing to overwrite $result_dir"
    mkdir -p "$result_dir"
    log_file="$result_dir/instrumentation.log"
    {
        printf 'head=%s\n' "$head_sha"
        printf 'utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        uname -a
        lscpu
        free -h
        java -version
        ./gradlew --version
        git status --short
    } >"$result_dir/host.txt" 2>&1
    cp "$manifest" "$result_dir/manifest.json"
    KLAUSE_LP_INSTRUMENTATION=1 ./gradlew \
        :klause:cleanAllTests :klause:jvmTest \
        --tests com.eignex.klause.lp.engine.LpInstrumentationHarness --info \
        | tee "$log_file"
    grep -q '^LP_INSTRUMENTATION ' "$log_file" || die "instrumentation result line was not emitted"
    grep '^LP_\(INSTRUMENTATION\|AUXILIARY_COST\) ' "$log_file" >"$result_dir/results.txt"
    sha256sum "$result_dir"/{host.txt,manifest.json,instrumentation.log,results.txt} \
        >"$result_dir/SHA256SUMS"
    printf 'wrote %s\n' "$result_dir"
}

print_reference_commands() {
    cat <<'COMMANDS'
# These commands write klause-bench/reference/{z3,cp-sat,scip}.csv. Run only in a task that owns them.
# The bounded reconciliation slices:
./gradlew :klause-bench:bench --args="reference suite=smtlib-qflra per-family=1 max=10 seed=1 jobs=1 workers=1 timeout=30000 label=w0-ref-qflra-v1"
./gradlew :klause-bench:bench --args="reference suite=smtlib-qflira per-family=1 max=10 seed=1 jobs=1 workers=1 timeout=30000 label=w0-ref-qflira-v1"
./gradlew :klause-bench:bench --args="reference suite=smtlib-qflia per-family=1 max=10 seed=1 jobs=1 workers=1 timeout=30000 label=w0-ref-qflia-v1"
./gradlew :klause-bench:bench --args="reference suite=smtlib-qfidl per-family=1 max=10 seed=1 jobs=1 workers=1 timeout=30000 label=w0-ref-qfidl-v1"
./gradlew :klause-bench:bench --args="reference suite=smtlib-qfrdl per-family=1 max=10 seed=1 jobs=1 workers=1 timeout=30000 label=w0-ref-qfrdl-v1"
./gradlew :klause-bench:bench --args="reference suite=mzn-bench per-family=1 max=60 seed=1 jobs=1 workers=1 timeout=30000 label=w0-ref-mzn-v1"
./gradlew :klause-bench:bench --args="reference suite=xcsp3-core jobs=1 workers=1 timeout=30000 label=w0-ref-xcsp3-core-v1"
./gradlew :klause-bench:bench --args="reference suite=mps-core jobs=1 workers=1 timeout=30000 label=w0-ref-mps-core-v1"
./gradlew :klause-bench:bench --args="reference suite=miplib2017 name=10teams,22433,23588,2club200v15p5scn,30_70_45_05_100,30_70_45_095_100,30n20b8,50v-10,8div-n59k10,8div-n59k11,CMS750_4,Test3 jobs=1 workers=1 timeout=30000 label=w0-ref-miplib12-v1"

# Full SMT reference coverage required by the Wave 0 exit; no max/per-family narrowing:
./gradlew :klause-bench:bench --args="reference suite=smtlib-qflra jobs=1 workers=1 timeout=30000 label=w0-ref-qflra-full-v1"
./gradlew :klause-bench:bench --args="reference suite=smtlib-qflira jobs=1 workers=1 timeout=30000 label=w0-ref-qflira-full-v1"
./gradlew :klause-bench:bench --args="reference suite=smtlib-qflia jobs=1 workers=1 timeout=30000 label=w0-ref-qflia-full-v1"
./gradlew :klause-bench:bench --args="reference suite=smtlib-qfidl jobs=1 workers=1 timeout=30000 label=w0-ref-qfidl-full-v1"
./gradlew :klause-bench:bench --args="reference suite=smtlib-qfrdl jobs=1 workers=1 timeout=30000 label=w0-ref-qfrdl-full-v1"
COMMANDS
}

print_baseline_commands() {
    cat <<'COMMANDS'
./gradlew :klause-cli:installJvmDist

# Run each command for rep=1,2,3, substituting the repetition in label. Do not overlap timed runs.
./gradlew -Dklause.bench.cache=false :klause-bench:bench --args="solve suite=mzn-bench per-family=1 max=60 seed=1 processors=1 timeout=3000 lp=default label=w0-mzn-default-r${rep}"
./gradlew -Dklause.bench.cache=false :klause-bench:bench --args="solve suite=mzn-bench per-family=1 max=60 seed=1 processors=1 timeout=3000 lp=off label=w0-mzn-off-r${rep}"
./gradlew -Dklause.bench.cache=false :klause-bench:bench --args="solve suite=xcsp3-core processors=1 timeout=3000 lp=default label=w0-xcsp3-default-r${rep}"
./gradlew -Dklause.bench.cache=false :klause-bench:bench --args="solve suite=xcsp3-core processors=1 timeout=3000 lp=off label=w0-xcsp3-off-r${rep}"
./gradlew -Dklause.bench.cache=false :klause-bench:bench --args="solve suite=mps-core processors=1 timeout=3000 lp=default label=w0-mps-default-r${rep}"
./gradlew -Dklause.bench.cache=false :klause-bench:bench --args="solve suite=mps-core processors=1 timeout=3000 lp=off label=w0-mps-off-r${rep}"
./gradlew -Dklause.bench.cache=false :klause-bench:bench --args="solve suite=miplib2017 name=10teams,22433,23588,2club200v15p5scn,30_70_45_05_100,30_70_45_095_100,30n20b8,50v-10,8div-n59k10,8div-n59k11,CMS750_4,Test3 processors=1 timeout=3000 lp=default label=w0-miplib12-default-r${rep}"
./gradlew -Dklause.bench.cache=false :klause-bench:bench --args="solve suite=miplib2017 name=10teams,22433,23588,2club200v15p5scn,30_70_45_05_100,30_70_45_095_100,30n20b8,50v-10,8div-n59k10,8div-n59k11,CMS750_4,Test3 processors=1 timeout=3000 lp=off label=w0-miplib12-off-r${rep}"

# SMT slice commands use the exact per-family/max/seed selections in the manifest.
./gradlew -Dklause.bench.cache=false :klause-bench:bench --args="solve suite=smtlib-qflra per-family=1 max=10 seed=1 engine=fixed processors=1 timeout=30000 lp=default label=w0-qflra-default-r${rep}"
./gradlew -Dklause.bench.cache=false :klause-bench:bench --args="solve suite=smtlib-qflra per-family=1 max=10 seed=1 engine=fixed processors=1 timeout=30000 lp=off label=w0-qflra-off-r${rep}"
./gradlew -Dklause.bench.cache=false :klause-bench:bench --args="solve suite=smtlib-qflira per-family=1 max=10 seed=1 engine=fixed processors=1 timeout=30000 lp=default label=w0-qflira-default-r${rep}"
./gradlew -Dklause.bench.cache=false :klause-bench:bench --args="solve suite=smtlib-qflira per-family=1 max=10 seed=1 engine=fixed processors=1 timeout=30000 lp=off label=w0-qflira-off-r${rep}"
./gradlew -Dklause.bench.cache=false :klause-bench:bench --args="solve suite=smtlib-qflia per-family=1 max=10 seed=1 engine=fixed processors=1 timeout=30000 lp=default label=w0-qflia-default-r${rep}"
./gradlew -Dklause.bench.cache=false :klause-bench:bench --args="solve suite=smtlib-qflia per-family=1 max=10 seed=1 engine=fixed processors=1 timeout=30000 lp=off label=w0-qflia-off-r${rep}"
./gradlew -Dklause.bench.cache=false :klause-bench:bench --args="solve suite=smtlib-qfidl per-family=1 max=10 seed=1 engine=fixed processors=1 timeout=30000 lp=default label=w0-qfidl-default-r${rep}"
./gradlew -Dklause.bench.cache=false :klause-bench:bench --args="solve suite=smtlib-qfidl per-family=1 max=10 seed=1 engine=fixed processors=1 timeout=30000 lp=off label=w0-qfidl-off-r${rep}"
./gradlew -Dklause.bench.cache=false :klause-bench:bench --args="solve suite=smtlib-qfrdl per-family=1 max=10 seed=1 engine=fixed processors=1 timeout=30000 lp=default label=w0-qfrdl-default-r${rep}"
./gradlew -Dklause.bench.cache=false :klause-bench:bench --args="solve suite=smtlib-qfrdl per-family=1 max=10 seed=1 engine=fixed processors=1 timeout=30000 lp=off label=w0-qfrdl-off-r${rep}"
COMMANDS
}

usage() {
    printf '%s\n' \
        "usage: $0 verify|preview|check|instrument|print-reference-commands|print-baseline-commands"
}

case "${1:-}" in
    verify) verify ;;
    preview) preview ;;
    check) check ;;
    instrument) instrument ;;
    print-reference-commands) print_reference_commands ;;
    print-baseline-commands) print_baseline_commands ;;
    *) usage; exit 2 ;;
esac
