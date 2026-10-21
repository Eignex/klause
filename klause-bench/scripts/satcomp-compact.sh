#!/usr/bin/env bash
# Draw the satcomp-compact selection: SAT Competition main-track CNF instances (2022-2024) that Kissat
# decided in a minute or two, small enough to keep, at most PER_FAMILY per family, balanced SAT/UNSAT.
# Prints one TSV row per instance: gbd-hash, family, name, result, kissat-seconds, year, xz-bytes, variables,
# clauses. The rows are what SatCompCompact.instances pins; rerun to redraw the list, then bump the suite version.
# Needs curl, unzip, sqlite3 and awk. Work files go to $WORK (default: a temporary directory).
# Usage: satcomp-compact.sh [count]
set -euo pipefail

COUNT="${1:-40}"
PER_FAMILY="${PER_FAMILY:-2}"
MIN_SEC="${MIN_SEC:-5}"
MAX_SEC="${MAX_SEC:-120}"
MAX_XZ_BYTES="${MAX_XZ_BYTES:-2097152}"
MAX_VARIABLES="${MAX_VARIABLES:-1000000}"
MAX_CLAUSES="${MAX_CLAUSES:-5000000}"
GBD="https://benchmark-database.de"
SC="https://satcompetition.github.io"
WORK="${WORK:-$(mktemp -d)}"
mkdir -p "$WORK"
cd "$WORK"

fetch() { [ -s "$2" ] || curl -fsSL --retry 3 -o "$2" "$1"; }

fetch "$GBD/getdatabase/meta" meta.db
fetch "$GBD/getdatabase/base" base.db
fetch "$SC/2022/downloads/sc2022-detailed-results.zip" sc2022.zip
fetch "$SC/2023/downloads/sc2023-detailed-results.zip" sc2023.zip
fetch "$SC/2024/downloads/detailed_results.zip" sc2024.zip
unzip -qo sc2022.zip main-seq.csv -d sc2022
unzip -qo sc2023.zip results_main_detailed.csv -d sc2023
unzip -qo sc2024.zip detailed_main.csv -d sc2024

# hash, verified result and Biere's Kissat entry's time from one year's main-track table; 10000 marks unsolved.
times() { # <csv> <year> <kissat column> <result column>
    awk -F, -v year="$2" -v solver="$3" -v result="$4" '
        NR == 1 { for (i = 1; i <= NF; i++) { if ($i == solver) k = i; if ($i == result) r = i; if ($i == "hash") h = i }; next }
        ($r == "sat" || $r == "unsat") { printf "%s\t%s\t%s\t%s\n", $h, $r, $k, year }' "$1"
}
{
    times sc2022/main-seq.csv 2022 kissat-sc2022-bulky verified-result
    times sc2023/results_main_detailed.csv 2023 kissat-3.1.0 vresult
    times sc2024/detailed_main.csv 2024 kissat-sc2024 vresult
} | awk -F'\t' -v lo="$MIN_SEC" -v hi="$MAX_SEC" '$3 >= lo && $3 <= hi && !seen[$1]++' > timed.tsv

sqlite3 -separator $'\t' meta.db > timed-meta.tsv <<SQL
ATTACH 'base.db' AS base;
CREATE TEMP TABLE timed(hash TEXT, result TEXT, seconds REAL, year INTEGER);
.mode tabs
.import timed.tsv timed
SELECT t.hash, f.family, (SELECT min(value) FROM filename n WHERE n.hash = t.hash), t.result, t.seconds, t.year, b.variables, b.clauses
FROM timed t JOIN features f ON f.hash = t.hash JOIN base.features b ON b.hash = t.hash
WHERE f.result = t.result AND f.family NOT IN ('unknown', 'empty') AND b.variables + 0 <= $MAX_VARIABLES AND b.clauses + 0 <= $MAX_CLAUSES
ORDER BY f.family, t.hash;
SQL

# The compressed size GBD serves, from a HEAD request per candidate, and each family's place in the draw: the POSIX
# cksum of its name, so the families a short list leaves out do not follow the alphabet. A name keeps the file name's
# stem with every character a problem name cannot hold (`#` starts a set-file comment) replaced by `_`.
while IFS=$'\t' read -r hash family filename result seconds year variables clauses; do
    bytes=$(curl -fsSI --retry 3 "$GBD/file/$hash" | awk 'tolower($1) == "content-length:" { print $2 + 0 }')
    [ "${bytes:-0}" -gt 0 ] && [ "$bytes" -le "$MAX_XZ_BYTES" ] || continue
    name="${filename%.xz}"; name="${name%.cnf}"; name="${name%.dimacs}"; name="${name%.sanitized}"; name="${name%.cnf}"
    name=$(printf '%s' "$name" | tr -c 'A-Za-z0-9._-' '_')
    key=$(printf '%s' "$family" | cksum | cut -d' ' -f1)
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$key" "$hash" "$family" "$name" "$result" "$seconds" "$year" "$bytes" "$variables" "$clauses"
done < timed-meta.tsv | sort -t$'\t' -k1,1n -k2,2 > candidates.tsv

# Up to PER_FAMILY rounds over the families in draw order, each taking a family's first instance (by hash) whose result
# is still under half of COUNT, until COUNT are taken.
awk -F'\t' -v per="$PER_FAMILY" -v count="$COUNT" '
    { rows[NR] = $0; fam[NR] = $3; res[NR] = $5; if (!($3 in seen)) { seen[$3] = 1; order[++families] = $3 } }
    END {
        quota = count / 2
        for (round = 1; round <= per; round++) for (f = 1; f <= families; f++) {
            if (taken["sat"] + taken["unsat"] >= count) break
            for (i = 1; i <= NR; i++) {
                if (fam[i] != order[f] || used[i] || taken[res[i]] >= quota) continue
                used[i] = 1; taken[res[i]]++
                sub(/^[^\t]*\t/, "", rows[i]); print rows[i]
                break
            }
        }
    }' candidates.tsv | sort -t$'\t' -k2,2 -k3,3
