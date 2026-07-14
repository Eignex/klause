#!/usr/bin/env bash
# 300s klause ENGINE sweep over the agreed subset of the MiniZinc Challenge corpus: every klause engine
# (mixed | ls | alns | cp) on each selection, so the curated portfolio and its single-family alternatives
# can be A/B'd head-to-head (feeds the #644 ALNS calibration). Reference-solver baselines live in
# run-baselines.sh; this file is klause-only. The `solve` metric writes one .out + .json per problem under
# klause-bench/output/<config>/, so the dir name encodes engine+settings+budget — nothing to copy here.
#
# klause runs as a subprocess, so the CLI dist must be current: installJvmDist below (installDist is a
# no-op that serves a stale binary — see klause-bench/README.md).
set -u
cd "$(git rev-parse --show-toplevel)"
./gradlew :klause-cli:installJvmDist -q

COP="suite=mzn-bench kind=cop per-family=1 name=elitserien,gfd-schedule,cargo,is/*,nfc,mario,evilshop,zephyrus"
CSP="suite=mzn-bench kind=csp per-family=1 name=multi-knapsack,oocsp_racks"

run() { echo ">>> solve $* ($(date +%H:%M))" >&2; ./gradlew :klause-bench:bench --args="solve $* timeout=300000" -q; }

# Every engine on both selections. alns/mixed hybridise LS+CP; ls/cp are the pure tracks. On a CSP alns
# degrades to its inner LS (no objective to optimise), but it is swept for completeness.
for SEL in "$COP" "$CSP"; do
  for ENG in mixed ls alns cp; do
    run "$SEL engine=$ENG processors=8"
  done
done
echo "KLAUSE-ENGINES-COMPLETE ($(date +%H:%M))" >&2
