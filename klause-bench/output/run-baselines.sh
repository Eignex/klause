#!/usr/bin/env bash
# 300s BASELINE-ONLY sweep over the agreed subset of the MiniZinc Challenge corpus.
# No klause — just the reference solvers, one per invocation:
#   Choco backs the complete-search tracks (free=p1, parallel/open=p8, fixed=annotation)
#   Yuck  backs the local-search track (ls)
# The `solve` metric writes one .out + .json per problem under klause-bench/output/<config>/ itself,
# so there's nothing to copy here — each config's dir name encodes solver+settings+budget.
set -u
cd "$(git rev-parse --show-toplevel)"
COP="suite=mzn-bench kind=cop per-family=1 name=elitserien,gfd-schedule,cargo,is/*,nfc,mario,evilshop,zephyrus"
CSP="suite=mzn-bench kind=csp per-family=1 name=multi-knapsack,oocsp_racks"

run() { echo ">>> solve $* ($(date +%H:%M))" >&2; ./gradlew :klause-bench:bench --args="solve $* timeout=300000" -q; }

for SEL in "$COP" "$CSP"; do
  run "$SEL backend=choco processors=8"            # parallel + open (multi-thread)
  run "$SEL backend=choco processors=1"            # free (single-core)
  run "$SEL backend=choco processors=1 fixed=true" # fixed (single-core, follow annotation)
  run "$SEL backend=yuck processors=8"             # ls (parallel local search)
done
echo "BASELINES-COMPLETE ($(date +%H:%M))" >&2
