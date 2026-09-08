#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd "$script_dir/../.." && pwd)
manifest="$repo_root/klause-bench/lp-wave0-manifest.json"
cache_home=$(getent passwd "$(id -u)" | cut -d: -f6)
corpus_root="${KLAUSE_BENCH_CORPUS_ROOT:-$cache_home/.cache/klause-bench/corpus}"
full_per_family=2147483647
miplib_names=10teams,22433,23588,2club200v15p5scn,30_70_45_05_100,30_70_45_095_100,30n20b8,50v-10,8div-n59k10,8div-n59k11,CMS750_4,Test3
mzn_compatible_names=$(jq -r \
    '.suiteSlices[] | select(.suite == "mzn-bench") | (.excluded | map(.[0])) as $excluded | .instances[] | select(. as $id | ($excluded | index($id) | not))' \
    "$manifest" | paste -sd, -)

die() {
    printf 'lp-wave0-validation: %s\n' "$*" >&2
    exit 1
}

require_tools() {
    command -v jq >/dev/null || die "jq is required"
    command -v sha256sum >/dev/null || die "sha256sum is required"
}

require_idle_window() {
    [[ "${KLAUSE_LP_IDLE_WINDOW:-}" == "1" ]] ||
        die "set KLAUSE_LP_IDLE_WINDOW=1 only for a coordinated idle-host window"
}

campaign_dir() {
    local measured_sha=$1
    printf '%s/klause-bench/output/lp-wave0-campaign-%s\n' "$repo_root" "$measured_sha"
}

require_campaign() {
    local measured_sha=$1
    local dir
    dir=$(campaign_dir "$measured_sha")
    [[ -d "$dir" ]] || die "campaign is absent: $dir"
    [[ "$(<"$dir/measured-sha.txt")" == "$measured_sha" ]] || die "campaign SHA mismatch in $dir"
    git -C "$repo_root" merge-base --is-ancestor "$measured_sha" HEAD ||
        die "campaign SHA $measured_sha is not an ancestor of HEAD"
}

record_command() {
    local file=$1
    shift
    printf '%q ' "$@" >"$file"
    printf '\n' >>"$file"
}

checksum_dir() {
    local dir=$1
    (
        cd "$dir"
        find . -type f ! -name SHA256SUMS -print0 |
            LC_ALL=C sort -z |
            xargs -0 sha256sum
    ) >"$dir/SHA256SUMS"
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

    verify_corpora
    printf 'manifest, prerequisite reference tables and frozen corpus inputs verified\n'
}

verify_corpora() {
    require_tools
    jq empty "$manifest"

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

reject_residual_selection() {
    local inherited="${JAVA_TOOL_OPTIONS:-} ${_JAVA_OPTIONS:-} ${GRADLE_OPTS:-}"
    [[ "$inherited" != *klause.bench.shard* ]] || die "remove inherited klause.bench.shard"
    [[ "$inherited" != *klause.bench.select.perFamily* ]] || die "remove inherited klause.bench.select.perFamily"
    [[ "$inherited" != *klause.bench.select.max* ]] || die "remove inherited klause.bench.select.max"
    [[ "$inherited" != *klause.bench.select.seed* ]] || die "remove inherited klause.bench.select.seed"
}

require_cli_java() {
    local version major
    version=$(java -XshowSettings:properties -version 2>&1 |
        awk -F= '/^[[:space:]]*java.version =/ { gsub(/[[:space:]]/, "", $2); print $2; exit }')
    major=${version%%.*}
    [[ "$major" =~ ^[0-9]+$ && "$major" -ge 25 ]] ||
        die "klause-cli baselines require Java 25 or newer; current java.version is ${version:-unknown}"
}

extract_preview_ids() {
    local preview_file=$1
    local ids_file=$2
    sed -n 's/^  \(.*\)  \[[A-Z0-9_]*\/[A-Z_]*\]$/\1/p' "$preview_file" | LC_ALL=C sort >"$ids_file"
}

write_full_preflight() {
    local dir=$1
    reject_residual_selection
    mkdir -p "$dir/full-smt"
    local suite key table expected_count expected_hash preview_file ids_file actual_count actual_hash
    for suite in smtlib-qflra smtlib-qflira smtlib-qflia smtlib-qfidl smtlib-qfrdl; do
        case "$suite" in
            smtlib-qflra) key=smtlib-qf_lra ;;
            smtlib-qflira) key=smtlib-qf_lira ;;
            smtlib-qflia) key=smtlib-qf_lia ;;
            smtlib-qfidl) key=smtlib-qf_idl ;;
            smtlib-qfrdl) key=smtlib-qf_rdl ;;
        esac
        table="$repo_root/klause-bench/reference/z3.csv"
        preview_file="$dir/full-smt/$suite.preview.txt"
        ids_file="$dir/full-smt/$suite.ids"
        (
            cd "$repo_root"
            ./gradlew -Dklause.bench.select.perFamily="$full_per_family" -q \
                :klause-bench:bench --args="preview suite=$suite"
        ) >"$preview_file"
        extract_preview_ids "$preview_file" "$ids_file"
        expected_count=$(jq -r --arg suite "$suite" '.campaignContract.fullSmtMembership[$suite].instances' "$manifest")
        expected_hash=$(jq -r --arg suite "$suite" '.campaignContract.fullSmtMembership[$suite].sortedIdsSha256' "$manifest")
        actual_count=$(wc -l <"$ids_file")
        actual_hash=$(sha256sum "$ids_file" | cut -d' ' -f1)
        [[ "$actual_count" == "$expected_count" ]] || die "$suite full membership is $actual_count, expected $expected_count"
        [[ "$actual_hash" == "$expected_hash" ]] || die "$suite full membership hash is $actual_hash, expected $expected_hash"
        awk -F, -v key="$key" 'NR > 1 && $1 == key { print $2 }' "$table" | LC_ALL=C sort \
            >"$dir/full-smt/$suite.existing.ids"
        LC_ALL=C comm -23 "$ids_file" "$dir/full-smt/$suite.existing.ids" \
            >"$dir/full-smt/$suite.missing.ids"
    done
    (
        cd "$dir/full-smt"
        for suite in smtlib-qflra smtlib-qflira smtlib-qflia smtlib-qfidl smtlib-qfrdl; do
            printf '%s selected=%s existing=%s missing=%s selectedSha256=%s missingSha256=%s\n' \
                "$suite" \
                "$(wc -l <"$suite.ids")" \
                "$(wc -l <"$suite.existing.ids")" \
                "$(wc -l <"$suite.missing.ids")" \
                "$(sha256sum "$suite.ids" | cut -d' ' -f1)" \
                "$(sha256sum "$suite.missing.ids" | cut -d' ' -f1)"
        done
    ) >"$dir/full-smt/coverage.txt"
}

write_slice_preflight() {
    local dir=$1
    mkdir -p "$dir/slices"
    local suite filters preview_file ids_file expected_file
    for suite in smtlib-qflra smtlib-qflira smtlib-qflia smtlib-qfidl smtlib-qfrdl; do
        filters="per-family=1 max=10 seed=1"
        preview_file="$dir/slices/$suite.preview.txt"
        ids_file="$dir/slices/$suite.ids"
        (
            cd "$repo_root"
            ./gradlew -q :klause-bench:bench --args="preview suite=$suite $filters"
        ) >"$preview_file"
        extract_preview_ids "$preview_file" "$ids_file"
        expected_file="$dir/slices/$suite.expected.ids"
        jq -r --arg suite "$suite" \
            '.suiteSlices[] | select(.suite == $suite) | .instances[] | if type == "array" then .[0] else . end' \
            "$manifest" | LC_ALL=C sort >"$expected_file"
        cmp -s "$ids_file" "$expected_file" || die "$suite frozen slice differs from the manifest"
    done
    for suite in mzn-bench xcsp3-core mps-core miplib2017; do
        case "$suite" in
            mzn-bench) filters="per-family=1 max=60 seed=1" ;;
            miplib2017) filters="name=$miplib_names" ;;
            *) filters="" ;;
        esac
        preview_file="$dir/slices/$suite.preview.txt"
        ids_file="$dir/slices/$suite.ids"
        (
            cd "$repo_root"
            ./gradlew -q :klause-bench:bench --args="preview suite=$suite $filters"
        ) >"$preview_file"
        extract_preview_ids "$preview_file" "$ids_file"
        expected_file="$dir/slices/$suite.expected.ids"
        jq -r --arg suite "$suite" \
            '.suiteSlices[] | select(.suite == $suite) | .instances[] | if type == "array" then .[0] else . end' \
            "$manifest" | LC_ALL=C sort >"$expected_file"
        cmp -s "$ids_file" "$expected_file" || die "$suite frozen slice differs from the manifest"
    done
    (
        cd "$repo_root"
        ./gradlew -q :klause-bench:bench \
            --args="preview suite=mzn-bench per-family=1 max=60 seed=1 name=$mzn_compatible_names"
    ) >"$dir/slices/mzn-bench-compatible.preview.txt"
    extract_preview_ids "$dir/slices/mzn-bench-compatible.preview.txt" \
        "$dir/slices/mzn-bench-compatible.ids"
    jq -r \
        '.suiteSlices[] | select(.suite == "mzn-bench") | (.excluded | map(.[0])) as $excluded | .instances[] | select(. as $id | ($excluded | index($id) | not))' \
        "$manifest" | LC_ALL=C sort >"$dir/slices/mzn-bench-compatible.expected.ids"
    cmp -s "$dir/slices/mzn-bench-compatible.ids" "$dir/slices/mzn-bench-compatible.expected.ids" ||
        die "MiniZinc compatible timing slice differs from the manifest exclusions"
    local table key expected_missing actual_missing
    for suite in smtlib-qflra smtlib-qflira smtlib-qflia smtlib-qfidl smtlib-qfrdl \
        mzn-bench xcsp3-core mps-core miplib2017; do
        case "$suite" in
            smtlib-qflra) table="$repo_root/klause-bench/reference/z3.csv"; key=smtlib-qf_lra ;;
            smtlib-qflira) table="$repo_root/klause-bench/reference/z3.csv"; key=smtlib-qf_lira ;;
            smtlib-qflia) table="$repo_root/klause-bench/reference/z3.csv"; key=smtlib-qf_lia ;;
            smtlib-qfidl) table="$repo_root/klause-bench/reference/z3.csv"; key=smtlib-qf_idl ;;
            smtlib-qfrdl) table="$repo_root/klause-bench/reference/z3.csv"; key=smtlib-qf_rdl ;;
            mzn-bench) table="$repo_root/klause-bench/reference/cp-sat.csv"; key=mzn-challenge ;;
            xcsp3-core) table="$repo_root/klause-bench/reference/cp-sat.csv"; key=xcsp3-core ;;
            mps-core) table="$repo_root/klause-bench/reference/scip.csv"; key=mps-core ;;
            miplib2017) table="$repo_root/klause-bench/reference/scip.csv"; key=miplib2017 ;;
        esac
        awk -F, -v key="$key" 'NR > 1 && $1 == key { print $2 }' "$table" | LC_ALL=C sort \
            >"$dir/slices/$suite.existing.ids"
        LC_ALL=C comm -23 "$dir/slices/$suite.ids" "$dir/slices/$suite.existing.ids" \
            >"$dir/slices/$suite.missing.ids"
        expected_missing=$(jq -r --arg suite "$suite" \
            '.campaignContract.sliceMissingAtPrerequisite[$suite]' "$manifest")
        actual_missing=$(wc -l <"$dir/slices/$suite.missing.ids")
        [[ "$actual_missing" == "$expected_missing" ]] ||
            die "$suite has $actual_missing missing frozen rows, expected $expected_missing"
    done
    (
        cd "$dir/slices"
        for suite in smtlib-qflra smtlib-qflira smtlib-qflia smtlib-qfidl smtlib-qfrdl \
            mzn-bench xcsp3-core mps-core miplib2017; do
            printf '%s selected=%s existing=%s missing=%s idsSha256=%s missingSha256=%s\n' \
                "$suite" \
                "$(wc -l <"$suite.ids")" \
                "$(wc -l <"$suite.existing.ids")" \
                "$(wc -l <"$suite.missing.ids")" \
                "$(sha256sum "$suite.ids" | cut -d' ' -f1)" \
                "$(sha256sum "$suite.missing.ids" | cut -d' ' -f1)"
        done
    ) >"$dir/slices/coverage.txt"
}

init_campaign() {
    verify
    local measured_sha dir
    measured_sha=$(git -C "$repo_root" rev-parse HEAD)
    dir=$(campaign_dir "$measured_sha")
    [[ ! -e "$dir" ]] || die "refusing to overwrite $dir"
    mkdir -p "$dir/preflight"
    printf '%s\n' "$measured_sha" >"$dir/measured-sha.txt"
    cp "$manifest" "$dir/manifest.json"
    {
        printf 'measuredSha=%s\n' "$measured_sha"
        printf 'utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        uname -a
        lscpu
        free -h
        java -version
        (cd "$repo_root" && ./gradlew --version)
        z3 -version
        minizinc --version
        minizinc --solvers
        docker version
        docker image inspect klause-xcsp3-cpsat:latest
        docker image inspect klause-scip:latest
        git -C "$repo_root" status --short
        git -C "$corpus_root/mzn-challenge" rev-parse HEAD
    } >"$dir/host-and-tools.txt" 2>&1
    write_full_preflight "$dir/preflight"
    write_slice_preflight "$dir/preflight"
    checksum_dir "$dir"
    printf 'initialized %s\n' "$dir"
}

reference_table_for() {
    case "$1" in
        mzn-bench|xcsp3-core) printf '%s/klause-bench/reference/cp-sat.csv\n' "$repo_root" ;;
        mps-core) printf '%s/klause-bench/reference/scip.csv\n' "$repo_root" ;;
        smtlib-qfidl|smtlib-qfrdl) printf '%s/klause-bench/reference/z3.csv\n' "$repo_root" ;;
        *) die "unsupported missing reference suite: $1" ;;
    esac
}

reference_key_for() {
    case "$1" in
        mzn-bench) printf 'mzn-challenge\n' ;;
        xcsp3-core|mps-core) printf '%s\n' "$1" ;;
        smtlib-qfidl) printf 'smtlib-qf_idl\n' ;;
        smtlib-qfrdl) printf 'smtlib-qf_rdl\n' ;;
        *) die "unsupported missing reference suite: $1" ;;
    esac
}

write_selected_reference_rows() {
    local ids_file=$1
    local table=$2
    local key=$3
    local output=$4
    awk -F, -v key="$key" \
        'FNR == NR { ids[$0] = 1; next } FNR == 1 { print; next } $1 == key && ($2 in ids) { print }' \
        "$ids_file" "$table" >"$output"
}

run_reference() {
    local measured_sha=$1
    local suite=$2
    local run_dir=$3
    local ids_file=$4
    shift 4
    local table key status
    table=$(reference_table_for "$suite")
    key=$(reference_key_for "$suite")
    [[ ! -e "$run_dir" ]] || die "refusing to overwrite $run_dir"
    mkdir -p "$run_dir"
    cp "$ids_file" "$run_dir/ids.txt"
    sha256sum "$table" >"$run_dir/reference-table-before.sha256"
    record_command "$run_dir/command.txt" "$@"
    set +e
    (
        cd "$repo_root"
        "$@"
    ) 2>&1 | tee "$run_dir/stdout.log"
    status=${PIPESTATUS[0]}
    set -e
    printf '%s\n' "$status" >"$run_dir/exit-status.txt"
    sha256sum "$table" >"$run_dir/reference-table-after.sha256"
    write_selected_reference_rows "$run_dir/ids.txt" "$table" "$key" "$run_dir/results.csv"
    checksum_dir "$run_dir"
    [[ "$status" == 0 ]] || die "$suite reference command exited $status; evidence retained in $run_dir"
}

reference_frozen() {
    require_idle_window
    reject_residual_selection
    local measured_sha=$1
    local suite=$2
    require_campaign "$measured_sha"
    verify_corpora
    case "$suite" in
        mzn-bench) filters="per-family=1 max=60 seed=1" ;;
        xcsp3-core|mps-core) filters="" ;;
        smtlib-qfidl|smtlib-qfrdl) filters="per-family=1 max=10 seed=1" ;;
        *) die "frozen missing reference suite must be mzn-bench, xcsp3-core, mps-core, smtlib-qfidl or smtlib-qfrdl" ;;
    esac
    local dir run_dir ids_file
    dir=$(campaign_dir "$measured_sha")
    run_dir="$dir/reference/$suite/frozen"
    ids_file="$dir/preflight/slices/$suite.ids"
    run_reference "$measured_sha" "$suite" "$run_dir" "$ids_file" \
        ./gradlew -Dklause.bench.cache=false :klause-bench:bench \
        --args="reference suite=$suite $filters jobs=1 workers=1 timeout=30000"
    if [[ "$suite" == smtlib-qfidl || "$suite" == smtlib-qfrdl ]]; then
        write_smt_source_hashes "$suite" "$run_dir/ids.txt" "$run_dir/sources.sha256"
        checksum_dir "$run_dir"
    fi
}

write_smt_source_hashes() {
    local suite=$1
    local ids_file=$2
    local output=$3
    local root prefix
    case "$suite" in
        smtlib-qfidl) root="$corpus_root/smtlib-qf_idl"; prefix=QF_IDL ;;
        smtlib-qfrdl) root="$corpus_root/smtlib-qf_rdl"; prefix=QF_RDL ;;
        *) die "not a full SMT reference suite: $suite" ;;
    esac
    while IFS= read -r id; do
        sha256sum "$root/$prefix/$id.smt2"
    done <"$ids_file" >"$output"
}

prepare_cli() {
    local measured_sha=$1
    require_cli_java
    require_campaign "$measured_sha"
    local dir status
    dir=$(campaign_dir "$measured_sha")/cli-build
    [[ ! -e "$dir" ]] || die "refusing to overwrite $dir"
    mkdir -p "$dir"
    record_command "$dir/command.txt" ./gradlew :klause-cli:installJvmDist
    set +e
    (
        cd "$repo_root"
        ./gradlew :klause-cli:installJvmDist
    ) 2>&1 | tee "$dir/stdout.log"
    status=${PIPESTATUS[0]}
    set -e
    printf '%s\n' "$status" >"$dir/exit-status.txt"
    checksum_dir "$dir"
    [[ "$status" == 0 ]] || die "CLI build exited $status; evidence retained in $dir"
}

cleanup_baseline_links() {
    [[ -z "${standard_dir:-}" || ! -L "$standard_dir" ]] || rm -f "$standard_dir"
    [[ -z "${standard_csv:-}" || ! -L "$standard_csv" ]] || rm -f "$standard_csv"
}

run_baseline_arm() {
    local measured_sha=$1
    local suite=$2
    local rep=$3
    local lp=$4
    local short=$5
    local timeout=$6
    local filters=$7
    local engine=$8
    local campaign run_dir label tag standard_dir standard_csv status arm
    campaign=$(campaign_dir "$measured_sha")
    arm=${lp:-single}
    run_dir="$campaign/baseline/$suite/rep-$rep/$arm"
    [[ ! -e "$run_dir" ]] || die "refusing to overwrite $run_dir"
    mkdir -p "$run_dir/results"
    label="w0-${measured_sha:0:12}-$short-$arm-r$rep"
    if [[ -n "$engine" ]]; then
        tag="klause-$engine-p1-t$((timeout / 1000))s"
    else
        tag="klause-p1-t$((timeout / 1000))s"
    fi
    [[ -z "$lp" ]] || tag="$tag-lp-$lp"
    tag="$tag-$label"
    standard_dir="$repo_root/klause-bench/output/$tag"
    standard_csv="$repo_root/klause-bench/output/$tag.csv"
    [[ ! -e "$standard_dir" && ! -e "$standard_csv" ]] ||
        die "standard bench output path already exists for $tag"
    : >"$run_dir/results.csv"
    ln -s "$run_dir/results" "$standard_dir"
    ln -s "$run_dir/results.csv" "$standard_csv"
    trap cleanup_baseline_links RETURN INT TERM
    local args="solve suite=$suite $filters processors=1 timeout=$timeout label=$label"
    [[ -z "$engine" ]] || args="$args engine=$engine"
    [[ -z "$lp" ]] || args="$args lp=$lp"
    record_command "$run_dir/command.txt" ./gradlew -Dklause.bench.cache=false :klause-bench:bench --args="$args"
    set +e
    (
        cd "$repo_root"
        ./gradlew -Dklause.bench.cache=false :klause-bench:bench --args="$args"
    ) 2>&1 | tee "$run_dir/stdout.log"
    status=${PIPESTATUS[0]}
    set -e
    cleanup_baseline_links
    trap - RETURN INT TERM
    printf '%s\n' "$status" >"$run_dir/exit-status.txt"
    checksum_dir "$run_dir"
    [[ "$status" == 0 ]] || die "$suite $arm repetition $rep exited $status; evidence retained in $run_dir"
}

baseline_pair() {
    require_idle_window
    reject_residual_selection
    require_cli_java
    local measured_sha=$1
    local suite=$2
    local rep=$3
    require_campaign "$measured_sha"
    verify_corpora
    [[ -f "$(campaign_dir "$measured_sha")/cli-build/exit-status.txt" ]] ||
        die "run prepare-cli before timed baselines"
    [[ "$(<"$(campaign_dir "$measured_sha")/cli-build/exit-status.txt")" == 0 ]] ||
        die "the retained CLI build did not succeed"
    [[ "$rep" =~ ^[123]$ ]] || die "baseline repetition must be 1, 2 or 3"
    local short timeout filters engine
    case "$suite" in
        mzn-bench)
            short=mzn; timeout=3000; engine=""
            if [[ "$rep" == 1 ]]; then
                filters="per-family=1 max=60 seed=1"
            else
                filters="per-family=1 max=60 seed=1 name=$mzn_compatible_names"
            fi
            ;;
        xcsp3-core) short=xcsp3; timeout=3000; filters=""; engine="" ;;
        mps-core) short=mps; timeout=3000; filters=""; engine="" ;;
        miplib2017) short=miplib12; timeout=3000; filters="name=$miplib_names"; engine="" ;;
        *) die "paired LP baseline suite must be mzn-bench, xcsp3-core, mps-core or miplib2017" ;;
    esac
    run_baseline_arm "$measured_sha" "$suite" "$rep" default "$short" "$timeout" "$filters" "$engine"
    run_baseline_arm "$measured_sha" "$suite" "$rep" off "$short" "$timeout" "$filters" "$engine"
    local pair_dir
    pair_dir="$(campaign_dir "$measured_sha")/baseline/$suite/rep-$rep"
    "$repo_root/klause-bench/output/compare.sh" \
        "$pair_dir/default/results" "$pair_dir/off/results" >"$pair_dir/compare.txt"
    checksum_dir "$pair_dir"
}

baseline_smt() {
    require_idle_window
    reject_residual_selection
    require_cli_java
    local measured_sha=$1
    local suite=$2
    local rep=$3
    require_campaign "$measured_sha"
    verify_corpora
    [[ -f "$(campaign_dir "$measured_sha")/cli-build/exit-status.txt" ]] ||
        die "run prepare-cli before timed baselines"
    [[ "$(<"$(campaign_dir "$measured_sha")/cli-build/exit-status.txt")" == 0 ]] ||
        die "the retained CLI build did not succeed"
    [[ "$rep" =~ ^[123]$ ]] || die "baseline repetition must be 1, 2 or 3"
    local short
    case "$suite" in
        smtlib-qflra) short=qflra ;;
        smtlib-qflira) short=qflira ;;
        smtlib-qflia) short=qflia ;;
        smtlib-qfidl) short=qfidl ;;
        smtlib-qfrdl) short=qfrdl ;;
        *) die "SMT baseline suite must be one of the five frozen arithmetic suites" ;;
    esac
    run_baseline_arm "$measured_sha" "$suite" "$rep" "" "$short" 30000 \
        "per-family=1 max=10 seed=1" fixed
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
    result_dir="$repo_root/klause-bench/output/lp-wave0-validation-v3-$head_sha"
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
    local instrumentation_status
    set +e
    KLAUSE_LP_INSTRUMENTATION=1 ./gradlew --no-build-cache \
        :klause:cleanAllTests :klause:jvmTest \
        --tests com.eignex.klause.lp.engine.LpInstrumentationHarness --info \
        | tee "$log_file"
    instrumentation_status=$?
    set -e
    grep -q 'LP_INSTRUMENTATION ' "$log_file" || die "instrumentation result line was not emitted"
    grep -E 'LP_(INSTRUMENTATION|AUXILIARY_COST) ' "$log_file" \
        | sed 's/^[[:space:]]*//' >"$result_dir/results.txt"
    sha256sum "$result_dir"/{host.txt,manifest.json,instrumentation.log,results.txt} \
        >"$result_dir/SHA256SUMS"
    printf 'wrote %s\n' "$result_dir"
    return "$instrumentation_status"
}

print_reference_commands() {
    cat <<'COMMANDS'
# Run after init-campaign and a coordinated idle-host reservation. Substitute the measured SHA.
KLAUSE_LP_IDLE_WINDOW=1 klause-bench/scripts/lp-wave0-validation.sh reference-frozen <sha> mzn-bench
KLAUSE_LP_IDLE_WINDOW=1 klause-bench/scripts/lp-wave0-validation.sh reference-frozen <sha> xcsp3-core
KLAUSE_LP_IDLE_WINDOW=1 klause-bench/scripts/lp-wave0-validation.sh reference-frozen <sha> mps-core
KLAUSE_LP_IDLE_WINDOW=1 klause-bench/scripts/lp-wave0-validation.sh reference-frozen <sha> smtlib-qfidl
KLAUSE_LP_IDLE_WINDOW=1 klause-bench/scripts/lp-wave0-validation.sh reference-frozen <sha> smtlib-qfrdl

# Existing LRA/LIRA/LIA rows are reused. Full IDL/RDL coverage is intentionally deferred; these
# commands run only the frozen per-family=1, max=10, seed=1 representative slices.
COMMANDS
}

print_baseline_commands() {
    cat <<'COMMANDS'
# Rebuild once, then run each suite for repetitions 1, 2 and 3 in coordinated idle-host windows.
klause-bench/scripts/lp-wave0-validation.sh prepare-cli <sha>
KLAUSE_LP_IDLE_WINDOW=1 klause-bench/scripts/lp-wave0-validation.sh baseline-pair <sha> <cp-or-mip-suite> <rep>
KLAUSE_LP_IDLE_WINDOW=1 klause-bench/scripts/lp-wave0-validation.sh baseline-smt <sha> <smt-suite> <rep>

# LP pairs: mzn-bench, xcsp3-core, mps-core, miplib2017. The four deterministic MiniZinc source
# incompatibilities run in repetition 1 only; repetitions 2 and 3 time the 15 compatible entries.
# SMT suites: smtlib-qflra, smtlib-qflira, smtlib-qflia, smtlib-qfidl, smtlib-qfrdl.
# SMT uses one uncached fixed/exact-theory configuration with no --lp flag; fixed rejects --lp default,
# and the SMT routing bound closure does not read the CLI LP emphasis.
COMMANDS
}

usage() {
    printf '%s\n' \
        "usage: $0 verify|preview|check|instrument|init-campaign|prepare-cli SHA" \
        "       $0 reference-frozen SHA SUITE" \
        "       $0 baseline-pair SHA SUITE REP" \
        "       $0 baseline-smt SHA SUITE REP" \
        "       $0 print-reference-commands|print-baseline-commands"
}

case "${1:-}" in
    verify) verify ;;
    preview) preview ;;
    check) check ;;
    instrument) instrument ;;
    init-campaign) init_campaign ;;
    prepare-cli) [[ $# == 2 ]] || die "prepare-cli requires SHA"; prepare_cli "$2" ;;
    reference-frozen) [[ $# == 3 ]] || die "reference-frozen requires SHA SUITE"; reference_frozen "$2" "$3" ;;
    baseline-pair) [[ $# == 4 ]] || die "baseline-pair requires SHA SUITE REP"; baseline_pair "$2" "$3" "$4" ;;
    baseline-smt) [[ $# == 4 ]] || die "baseline-smt requires SHA SUITE REP"; baseline_smt "$2" "$3" "$4" ;;
    print-reference-commands) print_reference_commands ;;
    print-baseline-commands) print_baseline_commands ;;
    *) usage; exit 2 ;;
esac
