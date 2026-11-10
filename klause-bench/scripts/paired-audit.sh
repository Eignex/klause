#!/usr/bin/env bash
# Audits a retained paired lab job read-only: paired-audit.sh --cases lab-cases.jsonl [--request request.json]
# [--execution execution.json] [--job job.json] [--arms a,b] [--treatment key] [--allow facet]
# [--control key=value] [--rows n]. Build the bench first with ./gradlew :klause-bench:installDist.
set -euo pipefail
lib="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/build/install/klause-bench/lib"
[[ -d "$lib" ]] || { echo "paired-audit: $lib missing; run ./gradlew :klause-bench:installDist" >&2; exit 2; }
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -Xmx1g -XX:ActiveProcessorCount=1 -cp "$lib/*" \
  com.eignex.klause.bench.lab.PairedAuditKt "$@"
