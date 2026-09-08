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
    command -v python3 >/dev/null || die "python3 is required"
    command -v sha256sum >/dev/null || die "sha256sum is required"
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
    while IFS=$'\t' read -r file base_expected campaign_expected; do
        local actual
        actual=$(sha256sum "$repo_root/$file" | cut -d' ' -f1)
        [[ "$actual" == "$base_expected" || "$actual" == "$campaign_expected" ]] ||
            die "reference table hash mismatch for $file: $actual"
    done < <(jq -r '
        . as $root |
        .referenceTablesAtBase[] as $base |
        [$base.file, $base.sha256,
         ([$root.referenceTablesAfterCampaign[]? | select(.file == $base.file) | .sha256][0] // $base.sha256)] |
        @tsv
    ' "$manifest")

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
            xcsp3-core) table="$repo_root/klause-bench/reference/cp-sat.csv"; key=klause-bench/smoke-corpus/xcsp3 ;;
            mps-core) table="$repo_root/klause-bench/reference/scip.csv"; key=klause-bench/smoke-corpus/mps ;;
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
        xcsp3-core) printf 'klause-bench/smoke-corpus/xcsp3\n' ;;
        mps-core) printf 'klause-bench/smoke-corpus/mps\n' ;;
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

repair_reference_snapshot() {
    local measured_sha=$1
    local suite=$2
    require_campaign "$measured_sha"
    case "$suite" in
        mps-core|xcsp3-core) ;;
        *) die "snapshot repair is only defined for mps-core or xcsp3-core" ;;
    esac
    local run_dir original table key expected_rows actual_rows
    run_dir="$(campaign_dir "$measured_sha")/reference/$suite/frozen"
    original="$run_dir/results.before-key-fix.csv"
    [[ -f "$run_dir/results.csv" ]] || die "reference snapshot is absent: $run_dir/results.csv"
    [[ ! -e "$original" ]] || die "refusing to overwrite $original"
    mv "$run_dir/results.csv" "$original"
    table=$(reference_table_for "$suite")
    key=$(reference_key_for "$suite")
    write_selected_reference_rows "$run_dir/ids.txt" "$table" "$key" "$run_dir/results.csv"
    expected_rows=$(( $(wc -l <"$run_dir/ids.txt") + 1 ))
    actual_rows=$(wc -l <"$run_dir/results.csv")
    [[ "$actual_rows" == "$expected_rows" ]] ||
        die "$suite repaired snapshot has $actual_rows rows, expected $expected_rows"
    {
        printf 'reason=reference CSV uses corpus-path suite key\n'
        printf 'runnerSha=%s\n' "$(git -C "$repo_root" rev-parse HEAD)"
        printf 'preserved=%s\n' "$(basename "$original")"
    } >"$run_dir/snapshot-repair.txt"
    checksum_dir "$run_dir"
}

audit_campaign() {
    local measured_sha=$1
    require_campaign "$measured_sha"
    require_tools
    python3 - "$repo_root" "$(campaign_dir "$measured_sha")" "$manifest" "$measured_sha" <<'PY'
import csv
import hashlib
import json
import math
import pathlib
import subprocess
import sys

repo, campaign, manifest_path = map(pathlib.Path, sys.argv[1:4])
measured_sha = sys.argv[4]
manifest = json.loads(manifest_path.read_text())
prerequisite = manifest["campaignPrerequisiteSha"]

def fail(message):
    raise SystemExit(f"lp-wave0-validation: {message}")

def ids_for(suite):
    spec = next(s for s in manifest["suiteSlices"] if s["suite"] == suite)
    return {entry[0] if isinstance(entry, list) else entry for entry in spec["instances"]}

def read_csv(path):
    with path.open(newline="") as stream:
        return list(csv.DictReader(stream))

def normalized(value):
    if value in (None, ""):
        return None
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value
    if value.lower() in ("true", "false"):
        return value.lower() == "true"
    try:
        return float(value)
    except ValueError:
        return value

reference_layout = {
    "smtlib-qfidl": ("z3.csv", "smtlib-qf_idl"),
    "smtlib-qfrdl": ("z3.csv", "smtlib-qf_rdl"),
    "mzn-bench": ("cp-sat.csv", "mzn-challenge"),
    "xcsp3-core": ("cp-sat.csv", "klause-bench/smoke-corpus/xcsp3"),
    "mps-core": ("scip.csv", "klause-bench/smoke-corpus/mps"),
}
reference_by_problem = {}
for suite, (table_name, table_suite) in reference_layout.items():
    table = read_csv(repo / "klause-bench/reference" / table_name)
    rows = [row for row in table if row["suite"] == table_suite and row["problem"] in ids_for(suite)]
    if {row["problem"] for row in rows} != ids_for(suite):
        fail(f"{suite} reference identities differ from the frozen manifest")
    reference_by_problem.update({row["problem"]: row for row in rows})
    snapshot = read_csv(campaign / "reference" / suite / "frozen" / "results.csv")
    if {row["problem"] for row in snapshot} != ids_for(suite):
        fail(f"{suite} retained reference snapshot identities differ from the frozen manifest")

for table_spec in manifest["referenceTablesAfterCampaign"]:
    rel = pathlib.Path(table_spec["file"])
    current = read_csv(repo / rel)
    base_text = subprocess.check_output(
        ["git", "-C", str(repo), "show", f"{prerequisite}:{rel.as_posix()}"], text=True
    )
    base = list(csv.DictReader(base_text.splitlines()))
    key = lambda row: (row["suite"], row["problem"])
    current_by_key = {key(row): row for row in current}
    base_by_key = {key(row): row for row in base}
    changed = [item for item, row in base_by_key.items() if current_by_key.get(item) != row]
    if changed:
        fail(f"{rel} changed {len(changed)} prerequisite rows")
    expected_added = set()
    for suite, (expected_table, table_suite) in reference_layout.items():
        if expected_table == rel.name:
            expected_added |= {(table_suite, problem) for problem in ids_for(suite)}
    actual_added = set(current_by_key) - set(base_by_key)
    if actual_added != expected_added:
        fail(f"{rel} additions differ from the frozen campaign slice")
    digest = hashlib.sha256((repo / rel).read_bytes()).hexdigest()
    if digest != table_spec["sha256"] or len(current) != table_spec["rows"]:
        fail(f"{rel} hash or row count differs from the campaign manifest")

expected_runs = []
for suite in ("mzn-bench", "xcsp3-core", "mps-core", "miplib2017"):
    excluded = {item[0] for item in next(s for s in manifest["suiteSlices"] if s["suite"] == suite).get("excluded", [])}
    for rep in (1, 2, 3):
        expected = ids_for(suite) - excluded
        for arm in ("default", "off"):
            expected_runs.append((suite, rep, arm, expected, 3000))
for suite in ("smtlib-qflra", "smtlib-qflira", "smtlib-qflia", "smtlib-qfidl", "smtlib-qfrdl"):
    for rep in (1, 2, 3):
        expected_runs.append((suite, rep, "single", ids_for(suite), 30000))

all_json = []
objective_sense_mismatches = []
verdict_conflicts = []
objective_conflicts = []
smt_signatures = {}
for suite, rep, arm, expected, budget in expected_runs:
    run = campaign / "baseline" / suite / f"rep-{rep}" / arm
    rows = read_csv(run / "results.csv")
    json_paths = sorted((run / "results").glob("*.json"))
    out_paths = sorted((run / "results").glob("*.out"))
    records = [json.loads(path.read_text()) for path in json_paths]
    all_json.extend(json_paths)
    if {row["problem"] for row in rows} != expected or {record["problem"] for record in records} != expected:
        fail(f"{suite} repetition {rep} {arm} identities differ from the frozen selection")
    if len(out_paths) != len(expected):
        fail(f"{suite} repetition {rep} {arm} has {len(out_paths)} raw outputs, expected {len(expected)}")
    by_problem = {record["problem"]: record for record in records}
    for row in rows:
        record = by_problem[row["problem"]]
        for field in ("maximize", "objective", "feasible", "proven", "budgetMs"):
            if normalized(row[field]) != normalized(record[field]):
                fail(f"{suite} repetition {rep} {arm} CSV/JSON mismatch for {row['problem']} {field}")
        if record["gitSha"] != measured_sha or record["solver"] != "klause":
            fail(f"{suite} repetition {rep} {arm} has foreign solver metadata")
        if record["processors"] != 1 or record["seed"] != 3 or record["budgetMs"] != budget:
            fail(f"{suite} repetition {rep} {arm} has unexpected deterministic settings")
        command = record["command"]
        if suite.startswith("smtlib-"):
            if record["engine"] != "fixed" or " -e fixed" not in command or "--lp" in command:
                fail(f"{suite} repetition {rep} is not the fixed no-LP configuration")
        elif f"--lp {arm}" not in command:
            fail(f"{suite} repetition {rep} {arm} does not encode its LP arm")
        oracle = reference_by_problem.get(record["problem"])
        if oracle is None:
            if suite.startswith("smtlib-"):
                table_name = "z3.csv"
            elif suite in ("mps-core", "miplib2017"):
                table_name = "scip.csv"
            else:
                table_name = "cp-sat.csv"
            candidates = [r for r in read_csv(repo / "klause-bench/reference" / table_name) if r["problem"] == record["problem"]]
            oracle = candidates[0] if len(candidates) == 1 else None
        if oracle is None:
            fail(f"no unambiguous oracle for {suite} {record['problem']}")
        oracle_feasible = normalized(oracle["feasible"])
        oracle_proven = normalized(oracle["proven"])
        oracle_objective = normalized(oracle["objective"])
        oracle_maximize = normalized(oracle["maximize"])
        if record["kind"] == "optimize" and record["maximize"] != oracle_maximize:
            objective_sense_mismatches.append((suite, rep, arm, record["problem"]))
        if oracle_proven and record["proven"] and record["feasible"] != oracle_feasible:
            verdict_conflicts.append((suite, rep, arm, record["problem"]))
        if oracle_proven and oracle_feasible and record["feasible"] and oracle_objective is not None:
            objective = normalized(record["objective"])
            if objective is not None:
                impossible = objective > oracle_objective + 1e-8 if oracle_maximize else objective < oracle_objective - 1e-8
                if impossible or (record["proven"] and not math.isclose(objective, oracle_objective, rel_tol=1e-8, abs_tol=1e-8)):
                    objective_conflicts.append((suite, rep, arm, record["problem"]))
    if suite.startswith("smtlib-"):
        signature = sorted((r["problem"], r["feasible"], r["proven"], r["objective"]) for r in records)
        smt_signatures.setdefault(suite, []).append(signature)

if len(expected_runs) != 39 or len(all_json) != 321:
    fail(f"campaign has {len(expected_runs)} baseline CSVs and {len(all_json)} JSON records")
for arm in ("default", "off"):
    log = (campaign / "baseline/mzn-bench/rep-1" / arm / "stdout.log").read_text()
    excluded = next(s for s in manifest["suiteSlices"] if s["suite"] == "mzn-bench")["excluded"]
    if "compile failed" not in log or any(problem not in log for problem, _ in excluded):
        fail(f"mzn-bench repetition 1 {arm} does not retain all four source-incompatible attempts")
if verdict_conflicts or objective_conflicts:
    fail(f"oracle audit found {len(verdict_conflicts)} verdict and {len(objective_conflicts)} objective conflicts")
expected_sense = {("xcsp3-core", rep, arm, "sum-opt-tiny") for rep in (1, 2, 3) for arm in ("default", "off")}
if set(objective_sense_mismatches) != expected_sense:
    fail(f"objective-sense metadata mismatches differ from the six documented sum-opt-tiny rows")
if any(len({json.dumps(signature, sort_keys=True) for signature in signatures}) != 1 for signatures in smt_signatures.values()):
    fail("SMT result signatures are not stable across repetitions")

for checksum_file in campaign.rglob("SHA256SUMS"):
    for line in checksum_file.read_text().splitlines():
        digest, rel = line.split("  ", 1)
        target = checksum_file.parent / rel.removeprefix("./")
        if not target.is_file() or hashlib.sha256(target.read_bytes()).hexdigest() != digest:
            fail(f"checksum mismatch in {checksum_file.relative_to(campaign)}: {rel}")

raw_cache = list((campaign / "reference-raw-rerun/cache").glob("*.json"))
if raw_cache:
    if len(raw_cache) != 39:
        fail(f"raw reference recovery has {len(raw_cache)} cache records, expected 39")
    with (campaign / "reference-raw-rerun/cache-index.tsv").open(newline="") as stream:
        index = list(csv.DictReader(stream, delimiter="\t"))
    expected_raw_ids = {
        (suite, problem)
        for suite in ("smtlib-qfidl", "smtlib-qfrdl", "mzn-bench", "xcsp3-core", "mps-core")
        for problem in ids_for(suite)
    }
    expected_raw_ids -= {
        ("mzn-bench", item[0])
        for item in next(s for s in manifest["suiteSlices"] if s["suite"] == "mzn-bench")["excluded"]
    }
    if {(row["suite"], row["problem"]) for row in index} != expected_raw_ids:
        fail("raw reference cache index identities differ from the 39 successful frozen attempts")
    for path in raw_cache:
        record = json.loads(path.read_text())
        if not record.get("command") or "rawOutput" not in record:
            fail(f"raw reference recovery record lacks command/output: {path.name}")
        rows = [row for row in index if row["cacheFile"] == path.name]
        if len(rows) != 1:
            fail(f"raw reference cache index does not uniquely name {path.name}")
        row = rows[0]
        if hashlib.sha256(record["command"].encode()).hexdigest() != row["commandSha256"]:
            fail(f"raw reference command hash mismatch for {path.name}")
        if hashlib.sha256(record["rawOutput"].encode()).hexdigest() != row["rawOutputSha256"]:
            fail(f"raw reference output hash mismatch for {path.name}")
    with (campaign / "reference-raw-rerun/source-error-index.tsv").open(newline="") as stream:
        error_index = list(csv.DictReader(stream, delimiter="\t"))
    expected_errors = {
        ("mzn-bench", item[0])
        for item in next(s for s in manifest["suiteSlices"] if s["suite"] == "mzn-bench")["excluded"]
    }
    if {(row["suite"], row["problem"]) for row in error_index} != expected_errors:
        fail("raw source-error index identities differ from the four frozen incompatibilities")
    error_dirs = [
        campaign / "reference-raw-rerun/source-errors" / row["directory"] / "exit-status.txt"
        for row in error_index
    ]
    if any(not path.is_file() or path.read_text().strip() == "0" for path in error_dirs):
        fail("raw reference recovery does not retain four source-incompatible failures")

print(f"measuredSha={measured_sha}")
print(f"auditorSha={subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD'], text=True).strip()}")
print("referenceRows=43")
print("baselineCsvFiles=39")
print("baselineRows=321")
print("baselineJsonFiles=321")
print("baselineOutFiles=321")
print("oracleVerdictConflicts=0")
print("oracleObjectiveConflicts=0")
print("objectiveSenseMetadataMismatches=6")
print("smtSignaturesStable=true")
print("nestedChecksumsVerified=true")
PY
}

finalize_campaign() {
    local measured_sha=$1
    require_campaign "$measured_sha"
    verify
    local dir
    dir=$(campaign_dir "$measured_sha")
    [[ ! -e "$dir/final-manifest.json" ]] || die "refusing to overwrite $dir/final-manifest.json"
    audit_campaign "$measured_sha" >"$dir/campaign-audit.txt"
    cp "$manifest" "$dir/final-manifest.json"
    (
        cd "$dir"
        find . -type f ! -name FINAL_SHA256SUMS -print0 |
            LC_ALL=C sort -z |
            xargs -0 sha256sum
    ) >"$dir/FINAL_SHA256SUMS"
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
# Run after init-campaign. Substitute the measured SHA. Independent suites may run concurrently only
# when they write different reference tables; never run two writers for the same CSV concurrently.
klause-bench/scripts/lp-wave0-validation.sh reference-frozen <sha> mzn-bench
klause-bench/scripts/lp-wave0-validation.sh reference-frozen <sha> xcsp3-core
klause-bench/scripts/lp-wave0-validation.sh reference-frozen <sha> mps-core
klause-bench/scripts/lp-wave0-validation.sh reference-frozen <sha> smtlib-qfidl
klause-bench/scripts/lp-wave0-validation.sh reference-frozen <sha> smtlib-qfrdl

# Existing LRA/LIRA/LIA rows are reused. Full IDL/RDL coverage is intentionally deferred; these
# commands run only the frozen per-family=1, max=10, seed=1 representative slices.
COMMANDS
}

print_baseline_commands() {
    cat <<'COMMANDS'
# Rebuild once, then run each suite for repetitions 1, 2 and 3. At most two independent suites run
# concurrently; each solver remains single-worker and every run has an isolated campaign directory.
klause-bench/scripts/lp-wave0-validation.sh prepare-cli <sha>
klause-bench/scripts/lp-wave0-validation.sh baseline-pair <sha> <cp-or-mip-suite> <rep>
klause-bench/scripts/lp-wave0-validation.sh baseline-smt <sha> <smt-suite> <rep>

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
        "       $0 repair-reference-snapshot SHA SUITE" \
        "       $0 audit-campaign SHA" \
        "       $0 finalize-campaign SHA" \
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
    repair-reference-snapshot) [[ $# == 3 ]] || die "repair-reference-snapshot requires SHA SUITE"; repair_reference_snapshot "$2" "$3" ;;
    audit-campaign) [[ $# == 2 ]] || die "audit-campaign requires SHA"; audit_campaign "$2" ;;
    finalize-campaign) [[ $# == 2 ]] || die "finalize-campaign requires SHA"; finalize_campaign "$2" ;;
    baseline-pair) [[ $# == 4 ]] || die "baseline-pair requires SHA SUITE REP"; baseline_pair "$2" "$3" "$4" ;;
    baseline-smt) [[ $# == 4 ]] || die "baseline-smt requires SHA SUITE REP"; baseline_smt "$2" "$3" "$4" ;;
    print-reference-commands) print_reference_commands ;;
    print-baseline-commands) print_baseline_commands ;;
    *) usage; exit 2 ;;
esac
