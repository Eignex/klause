#!/usr/bin/env bash
# Pairs fixed-work-sat rows from `lab cases ID --json` on stdin by comparison key and repeat:
# deploy/lab cases ID --json | compare-fixed-work.sh CONTROL CANDIDATE [--allow FACET[,FACET]]...
# Prints one JSON line per row, then a summary line. Build the bench first with ./gradlew :klause-bench:installDist.
set -euo pipefail
lib="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/build/install/klause-bench/lib"
[[ -d "$lib" ]] || { echo "compare-fixed-work: $lib missing; run ./gradlew :klause-bench:installDist" >&2; exit 2; }
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -Xmx1g -XX:ActiveProcessorCount=1 -XX:+UseSerialGC -cp "$lib/*" \
  com.eignex.klause.bench.lab.FixedWorkComparisonKt "$@"
