#!/usr/bin/env bash
# 300s BASELINE-ONLY sweep over the ENTIRE MiniZinc competition corpus (no name filter, no
# per-family cap): 89 COP + 41 CSP = 130 problems. References only, one solver per invocation.
#   Choco p8 (parallel+open), p1 (free), fixed (annotation); Yuck (ls).
# Already-solved problems re-hit the cache (same content+budget+solver-label) → instant.
# The `solve` metric writes one .out + .json per problem under klause-bench/output/<config>/ itself.
set -u
cd "$(git rev-parse --show-toplevel)"
COP="suite=mzn-bench kind=cop"
CSP="suite=mzn-bench kind=csp"

run() { echo ">>> solve $* ($(date +%H:%M))" >&2; ./gradlew :klause-bench:bench --args="solve $* timeout=300000" -q; }

for SEL in "$COP" "$CSP"; do
  run "$SEL backend=choco processors=8"   # parallel + open
  run "$SEL backend=choco processors=1"   # free
  run "$SEL backend=choco fixed=true"     # fixed
  run "$SEL backend=yuck"                 # ls
done
echo "FULL-BASELINES-COMPLETE ($(date +%H:%M))" >&2
