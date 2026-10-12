#!/usr/bin/env bash
# 300s sweep of each Klause engine over the selected MiniZinc Challenge problems.
# Outputs: one .out and .json per problem under output/<config>/.
# Rebuild the subprocess CLI with installJvmDist; see klause-bench/README.md.
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
