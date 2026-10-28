#!/usr/bin/env bash
set -euo pipefail
# A pure translation: read one legacy experiment or schedule from stdin and write its replacement.
jq '
  def parameters:
    with_entries(select(.key | IN("ref", "label", "backend", "solver", "fixed", "param.source-validation", "param.source-validation-gzip") | not))
    | with_entries(.key |= ({timeout:"timeout-ms", processors:"cpus", engine:"engines"}[.] // .));
  def convert:
    if .bench then .
    elif .analyze then .
    elif .profile then
      .repository = "Eignex/klause"
      | if (.profile.script // null) == null and (.profile.classpath // [] | length) == 0 then
          .profile = (.profile + {script:"klause-bench/scripts/lab-profile.sh",
            args:(["--debug=" + (.base.debug // "true"), "--cpus=" + (.base.processors // "1"), .profile.main] + (.profile.args // [])),
            buildTasks:[":klause-cli:installJvmDist", ":klause-bench:installDist", ":klause-bench:prepareLabRuntime"]} | del(.main))
        else . end
    else
      (.base // {}) as $base
      | (.problems // [] | if type == "object" then [.] else . end) as $selections
      | if ($selections | length) == 0 or (.problemList // [] | length) != 0 then
          error("exact legacy confirmation lists require a fresh frozen SDK plan; do not resubmit them as selections")
        else . end
      | ($base.backend == "reference") as $reference
      | .repository = "Eignex/klause"
      | .bench = {manifest:"klause-bench/lab/bench.json", sourceValidation:($reference | not),
          request:{parameters:({selections:($selections | map(del(.reference)) | tojson)} + ($base | parameters))}}
      | if (.seeds // [] | length) > 0 then .bench.request.parameters["solver-seeds"] = (.seeds | map(tostring) | join(",")) else . end
      | .bench.referenceFilters = ($selections | map(select(.suite != null) | {key:.suite,value:(.reference // if $reference then "any" else "decided" end)}) | from_entries)
      | .base = ($base | with_entries(select(.key | IN("ref", "label") or ($reference and IN("backend", "solver", "timeout", "processors", "fixed")))))
      | if .configs then .configs |= map(. as $config | ($config | parameters | with_entries(.key |= "param." + .)) + ($config | with_entries(select(.key | IN("ref", "label"))))) else . end
      | if .grid then .grid |= with_entries(if .key == "ref" then . else .key |= "param." + ({timeout:"timeout-ms", processors:"cpus", engine:"engines"}[.] // .) end) else . end
      | del(.problems, .problemList, .seeds, .measurement)
    end;
  if .experiment then .experiment |= convert else convert end
'
