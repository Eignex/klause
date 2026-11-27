#!/usr/bin/env bash
set -euo pipefail

missing_poms() {
    # Use Gradle's failure report so cached misses need no live HTTP diagnostic in the log.
    awk -v project_failure="A problem occurred configuring project ':buildSrc'." \
        -v classpath_failure="> Could not resolve all artifacts for configuration 'classpath'." '
        /^> Task / { ran_task = 1 }
        /^\* What went wrong:$/ { reports++; in_report = 1; next }
        /^\* / { in_report = 0 }
        !in_report || !NF { next }
        $0 == project_failure { build_src = 1; next }
        $0 == classpath_failure { classpath = 1; next }

        pending_url != "" {
            if ($0 ~ /^ +Searched in the following locations:$/) { searched = 1; next }
            if (searched && $1 == "-" && $2 == pending_url && NF == 2) {
                if (!seen[pending_url]++) urls[++count] = pending_url
                pending_url = ""
                searched = 0
                next
            }
            exit 1
        }
        /^ +> Could not find org\.jetbrains\.kotlin:kotlin-[a-z0-9-]+:[0-9][a-zA-Z0-9._-]*\.$/ {
            sub(/\.$/, "", $5)
            split($5, coordinate, ":")
            artifact = coordinate[2]
            version = coordinate[3]
            pending_url = "https://plugins.gradle.org/m2/org/jetbrains/kotlin/" artifact "/" version "/" artifact "-" version ".pom"
            if (artifact == "kotlin-gradle-plugins-bom") bom = 1
            next
        }
        /^ +> Could not resolve org\.jetbrains\.kotlin:kotlin-gradle-plugin:[0-9][a-zA-Z0-9._-]*\.$/ { next }
        /^ +> Could not parse POM https:\/\/plugins\.gradle\.org\/m2\/org\/jetbrains\/kotlin\/kotlin-gradle-plugin\/[a-zA-Z0-9._\/-]+\.pom$/ { next }
        /^> There (is 1 more failure|are [0-9]+ more failures) with identical causes?\.$/ { next }
        /^ *>/ || /^ *- / { unexpected = 1 }
        END {
            if (ran_task || reports != 1 || !build_src || !classpath || !bom || unexpected || pending_url != "") exit 1
            if (count == 0 || count > 10) exit 1
            for (i = 1; i <= count; i++) print urls[i]
        }
    ' "$1"
}

log=$(mktemp)
trap 'rm -f -- "$log"' EXIT
if "$@" --console=plain 2>&1 | tee "$log"; then
    exit 0
else
    statuses=("${PIPESTATUS[@]}")
fi
((statuses[0] != 0)) || exit "${statuses[1]}"
((statuses[1] == 0)) || exit "${statuses[0]}"

urls=$(missing_poms "$log") || exit "${statuses[0]}"
while IFS= read -r url; do
    status=$(curl --disable --silent --show-error --location --head --output /dev/null --write-out '%{http_code}' \
        --proto '=https' --proto-redir '=https' --connect-timeout 5 --max-time 10 "$url") || exit "${statuses[0]}"
    [[ "$status" == 200 ]] || exit "${statuses[0]}"
done <<< "$urls"

printf '\nKotlin metadata is available after a buildSrc classpath miss; retrying once with refreshed dependencies.\n'
# Gradle caches missing metadata per repository.
refresh=(--refresh-dependencies)
for arg; do
    if [[ "$arg" == --refresh-dependencies ]]; then
        refresh=()
        break
    fi
done
"$@" --console=plain "${refresh[@]}"
