#!/usr/bin/env bash
set -euo pipefail
: "${LAB_WORKTREE:?lab source root is required}"
: "${OUT:?lab output directory is required}"
runtime="${1:?usage: lp-lifecycle-qualification.sh jvm|native [repeats]}"
repeats="${2:-3}"
[[ "$runtime" == jvm || "$runtime" == native ]] || exit 2
[[ "$repeats" =~ ^[1-5]$ ]] || exit 2
qualification_output="$OUT"
revision=73cac48c5340d775a477087198611862559be250
for source in afiro adlittle avgas blending; do
  case "$source" in
    afiro) digest=9cd304f02717cbd6f85068cb777b69d28539b22a4868ae0f0fb425f514f0eea5 ;;
    adlittle) digest=ed99da009e35279828219ff7f04a2cd4f170692bedf58f704b622338e4adc1f9 ;;
    avgas) digest=10d0e68381321ee9b6a22a53a33d07f7740070b0a077a78ef742a9e88eca8213 ;;
    blending) digest=3aca63144ae6586f5b47e8cdfc75edf7fd191bae7772a6cf1be607c5a85a82bf ;;
  esac
  OUT="$qualification_output/$source" "$LAB_WORKTREE/klause-bench/scripts/lp-lifecycle-profile.sh" \
    "mps-$source" "$repeats" "$runtime" checkpoint-feasibility "$digest" "$revision"
done
