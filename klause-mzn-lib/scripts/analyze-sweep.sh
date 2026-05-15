#!/usr/bin/env bash
# Summarize a sweep.csv file. Counts outcomes, lists which predicates are
# blocking the most problems, and prints the slowest solved instances.
#
# Run: ./klause-mzn-lib/scripts/analyze-sweep.sh /tmp/sweep.csv

set -uo pipefail
CSV="${1:-/tmp/sweep.csv}"

if [[ ! -s "$CSV" ]]; then
  echo "analyze: empty or missing csv at $CSV" >&2
  exit 2
fi

echo "=== outcome counts ==="
awk -F',' 'NR > 1 { c[$3]++ } END { for (k in c) printf "  %-13s %4d\n", k, c[k] }' "$CSV" | sort -k2 -nr

echo ""
echo "=== top blocking predicates (unsupported) ==="
awk -F',' 'NR > 1 && $3 == "unsupported" { gsub(/"/, "", $5); c[$5]++ } END { for (k in c) printf "  %-30s %4d\n", k, c[k] }' "$CSV" | sort -k2 -nr | head -15

echo ""
echo "=== mzn-error fingerprints ==="
awk -F',' 'NR > 1 && $3 == "mzn-error" { gsub(/"/, "", $5); c[$5]++ } END { for (k in c) printf "  %-60s %4d\n", k, c[k] }' "$CSV" | sort -k2 -nr | head -10

echo ""
echo "=== solved instances (elapsed seconds, slowest first) ==="
awk -F',' 'NR > 1 && $3 == "solved" { printf "  %3ds  %s/%s\n", $4, $1, $2 }' "$CSV" | sort -nr | head -15

echo ""
echo "=== parse-error fingerprints (klause-side bugs) ==="
awk -F',' 'NR > 1 && $3 == "parse-error" { gsub(/"/, "", $5); c[$5]++ } END { for (k in c) printf "  %-80s %4d\n", k, c[k] }' "$CSV" | sort -k2 -nr | head -10
