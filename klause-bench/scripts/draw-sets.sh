#!/usr/bin/env bash
set -euo pipefail
bench="$(cd "$(dirname "$0")/.." && pwd)"
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$bench/build/install/klause-bench/lib/*" com.eignex.klause.bench.tools.MakeSetsKt "$@"
