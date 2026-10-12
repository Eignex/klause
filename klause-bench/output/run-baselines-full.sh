#!/usr/bin/env bash
# 300s reference sweep over the full MiniZinc competition corpus.
# Runs Choco parallel, free and fixed tracks plus Yuck local search; completed solves replay from cache.
set -u
cd "$(git rev-parse --show-toplevel)"
COP="suite=mzn-bench kind=cop"
CSP="suite=mzn-bench kind=csp"

run() { echo ">>> solve $* ($(date +%H:%M))" >&2; ./gradlew :klause-bench:bench --args="solve $* timeout=300000" -q; }

for SEL in "$COP" "$CSP"; do
  run "$SEL backend=choco processors=8"            # parallel + open (multi-thread)
  run "$SEL backend=choco processors=1"            # free (single-core)
  run "$SEL backend=choco processors=1 fixed=true" # fixed (single-core, follow annotation)
  run "$SEL backend=yuck processors=8"             # ls (parallel local search)
done
echo "FULL-BASELINES-COMPLETE ($(date +%H:%M))" >&2
