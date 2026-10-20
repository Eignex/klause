#!/usr/bin/env bash
# MiniZinc Challenge pairwise scoring of saved records, preserving exact objective quality.
# Usage: compare.sh [--complete|--incomplete] <dirA> <dirB>
set -eu
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIN="$ROOT/klause-bench/build/install/klause-bench/bin/klause-bench"
[ -x "$BIN" ] || { echo "building klause-bench dist…" >&2; (cd "$ROOT" && ./gradlew -q :klause-bench:installDist); }
exec "$BIN" compare "$@"
