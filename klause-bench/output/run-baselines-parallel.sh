#!/usr/bin/env bash
# Parallel baseline sweep: each config runs as its own bench JVM via the installDist binary (NOT
# gradle — so no daemon contention). Each writes a distinct output/<config>/ dir and distinct cache
# keys, so concurrent runs never collide; already-solved instances replay from cache.
# Cores at peak ≈ choco-p8(8) + yuck-p8(8) + 2×choco-p1(2) = 18 of 20.
# Prereq: ./gradlew :klause-bench:installDist
set -u
cd "$(git rev-parse --show-toplevel)/klause-bench"   # so output/ and build/bench-cache resolve here
BIN=build/install/klause-bench/bin/klause-bench
COP="suite=mzn-bench kind=cop"
CSP="suite=mzn-bench kind=csp"

run() { # logname  solve-args...
  local name="$1"; shift
  echo ">>> $name ($(date +%H:%M))" >&2
  "$BIN" solve "$@" timeout=300000 > "/tmp/par-$name.log" 2>&1
  echo "<<< $name done ($(date +%H:%M))" >&2
}

for slice in cop csp; do
  SEL=$([ "$slice" = cop ] && echo "$COP" || echo "$CSP")
  echo "=== $slice slice (4 configs in parallel) ===" >&2
  run "$slice-choco8"  $SEL backend=choco processors=8 &            # parallel + open
  run "$slice-choco1"  $SEL backend=choco processors=1 &            # free
  run "$slice-chocofx" $SEL backend=choco processors=1 fixed=true & # fixed
  run "$slice-yuck"    $SEL backend=yuck processors=8 &             # ls
  wait
done
echo "PARALLEL-BASELINES-COMPLETE ($(date +%H:%M))" >&2
