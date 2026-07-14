#!/usr/bin/env bash
# Set-cover credit between per-run result CSVs (reference-table schema, emitted by `bench solve` as
# output/<config>.csv). Joins on (suite, problem), picks each instance's winner(s) — COP: best
# objective (direction-aware, a proven optimum breaking equal-value ties); CSP: fastest decided run —
# then reports win-share + a greedy diverse set-cover. `--by structure|format` slices the credit
# within each feature-column value. Config-level credit (one CSV per config); arm-level credit is the
# same call over per-arm single-solver CSVs. Pass a committed `reference/<solver>.csv` as a file to
# score against that solver's baseline.
#
#   credit.sh [--by structure|format] <a.csv> <b.csv> [c.csv ...]
#
# (compare.sh is the separate MiniZinc-Challenge Borda pairwise scorer over the saved output dirs.)
set -eu
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIN="$ROOT/klause-bench/build/install/klause-bench/bin/klause-bench"
[ -x "$BIN" ] || { echo "building klause-bench dist…" >&2; (cd "$ROOT" && ./gradlew -q :klause-bench:installDist); }
exec "$BIN" credit "$@"
