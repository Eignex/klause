#!/usr/bin/env bash
# Offline frozen-source descriptor and held-out exposure checks; reads metadata only.
#   source-eligibility.sh check DESCRIPTOR [--manifest EXPOSURES.json]
#   source-eligibility.sh exposures --use USE --label LABEL LAB_CASES.jsonl...
# Requires `./gradlew :klause-bench:installDist`.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
lib="$root/klause-bench/build/install/klause-bench/lib"
[[ -d "$lib" ]] || { echo "missing $lib; run ./gradlew :klause-bench:installDist" >&2; exit 2; }
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -Xmx1g "-Dklause.workspace.root=$root" -cp "$lib/*" \
  com.eignex.klause.bench.lab.SourceEligibilityKt "$@"
