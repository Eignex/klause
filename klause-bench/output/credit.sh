#!/usr/bin/env bash
# Win-share and greedy set-cover credit over per-run CSVs, joined by (suite, problem).
# Usage: credit.sh [--by structure|format] <a.csv> <b.csv> [c.csv ...]
set -eu
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIN="$ROOT/klause-bench/build/install/klause-bench/bin/klause-bench"
[ -x "$BIN" ] || { echo "building klause-bench dist…" >&2; (cd "$ROOT" && ./gradlew -q :klause-bench:installDist); }
exec "$BIN" credit "$@"
