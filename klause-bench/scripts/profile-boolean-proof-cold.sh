#!/usr/bin/env bash
set -euo pipefail

proof_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
proof_output=${OUT:?OUT must name the retained lab output directory}
proof_java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
proof_seeds=1,3
proof_repeats=2
proof_suite=
proof_problem=
proof_arguments=()
for argument in "$@"; do
    case "$argument" in
        seeds=*) proof_seeds=${argument#*=} ;;
        cold-repeats=*) proof_repeats=${argument#*=} ;;
        seed=*|repeats=*|warmups=*|control=*)
            echo "Use seeds and cold-repeats; each child owns its control and warmup settings" >&2
            exit 2 ;;
        suite=*) proof_suite=${argument#*=}; proof_arguments+=("$argument") ;;
        problem=*) proof_problem=${argument#*=}; proof_arguments+=("$argument") ;;
        *) proof_arguments+=("$argument") ;;
    esac
done
[[ "$proof_repeats" =~ ^[1-6]$ && -n "$proof_suite" && -n "$proof_problem" ]]
IFS=, read -r -a proof_seed_values <<< "$proof_seeds"
for proof_seed in "${proof_seed_values[@]}"; do
    [[ "$proof_seed" =~ ^-?[0-9]+$ ]]
done
mkdir -p "$proof_output/preparation"
printf '%s\n' "$@" > "$proof_output/request.txt"
proof_time_format='elapsedSeconds\t%e\nuserCpuSeconds\t%U\nsystemCpuSeconds\t%S\nmaxResidentKiB\t%M\nexitStatus\t%x'
# Resolve the common corpus before either cold control; retain this preparation cost separately.
/usr/bin/time -f "$proof_time_format" -o "$proof_output/preparation/process.tsv" \
    "$proof_root/klause-bench/build/install/klause-bench/bin/klause-bench" select \
    "suite=$proof_suite" "name=$proof_problem" files=true \
    > "$proof_output/preparation/files.jsonl" 2> "$proof_output/preparation/stderr.txt"
[[ -s "$proof_output/preparation/files.jsonl" ]]
proof_failed=0
for proof_seed in "${proof_seed_values[@]}"; do
    for ((proof_repeat=0; proof_repeat<proof_repeats; proof_repeat++)); do
        proof_modes=(tasks replicas)
        if ((proof_repeat % 2 == 1)); then proof_modes=(replicas tasks); fi
        for proof_mode in "${proof_modes[@]}"; do
            proof_case="$proof_output/seed-$proof_seed/repeat-$proof_repeat/$proof_mode"
            mkdir -p "$proof_case"
            proof_status=0
            env OUT="$proof_case" /usr/bin/time -f "$proof_time_format" -o "$proof_case/process.tsv" \
                "$proof_java" -Xmx2560m -XX:ActiveProcessorCount=1 \
                --enable-native-access=ALL-UNNAMED --add-modules=jdk.incubator.vector \
                "-Dklause.workspace.root=$proof_root" \
                -cp "$proof_root/klause-bench/build/install/klause-bench/lib/*" \
                com.eignex.klause.bench.profile.BooleanProofSchedulerProfile \
                "${proof_arguments[@]}" "seed=$proof_seed" "control=$proof_mode" warmups=0 repeats=1 \
                > "$proof_case/stdout.txt" 2> "$proof_case/stderr.txt" || proof_status=$?
            printf '%s\n' "$proof_status" > "$proof_case/exit.txt"
            if ((proof_status != 0)); then proof_failed=1; fi
        done
    done
done
exit "$proof_failed"
