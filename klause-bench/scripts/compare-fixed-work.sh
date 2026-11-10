#!/usr/bin/env bash
set -euo pipefail
# Pair fixed-work-sat rows from `lab cases ID --json` on stdin by comparison key and repeat.
# Prints one JSON line per row, then a summary line. Throughput ratios are candidate/control and
# exist only for rows whose arms both completed comparable work along the same trajectory prefix.
if [[ $# -ne 2 ]]; then
  echo "usage: deploy/lab cases ID --json | $0 CONTROL-ARM CANDIDATE-ARM" >&2
  exit 2
fi
jq -s -c --arg control "$1" --arg candidate "$2" '
  def arm($row):
    if $row == null then null else
      ($row.record.details // {}) as $d | ($d.measured // {}) as $m | {
        status: $row.status, record: $row.record.status, host: $row.host,
        revision: $d.identity.revision, engine: $d.identity.engine,
        measurement: $d.measurement, comparable: ($d.comparable == true),
        interrupted: ($d.interrupted != false), emergencyKilled: ($d.process.emergencyKilled == true),
        stop: $m.stop, result: $m.result,
        prefixDigest: $m.trajectory.prefix.digest, digest: $m.trajectory.digest,
        checkpoints: ($m.trajectory.checkpoints // []),
        timeNs: ($m.limitPoint.elapsedNs // $m.measured.elapsedNs),
        cpuNs: ($m.limitPoint.threadCpuNs // $m.measured.threadCpuNs),
        processCpuNs: $m.measured.processCpuNs, preparationNs: $m.preparation.elapsedNs,
        work: $m.counters.work, decisions: $m.counters.decisions,
        selectorConflicts: $m.trajectory.conflicts
      }
    end;
  def ratio($a; $b):
    if ($a | type) == "number" and ($b | type) == "number" and $a > 0 then $b / $a else null end;
  def median:
    sort | if length == 0 then null
    elif length % 2 == 1 then .[(length / 2) | floor]
    else (.[length / 2 - 1] + .[length / 2]) / 2 end;
  def divergence($a; $b):
    [range([($a.checkpoints | length), ($b.checkpoints | length)] | min)
      | select($a.checkpoints[.].digest != $b.checkpoints[.].digest)] as $differ
    | {
        firstDifferentCheckpointEvents: (if ($differ | length) > 0 then $a.checkpoints[$differ[0]].events else null end),
        lastCommonCheckpointEvents: (
          [range([($a.checkpoints | length), ($b.checkpoints | length)] | min)
            | select(. < ($differ[0] // 1e18)) | $a.checkpoints[.].events] | last)
      };
  [group_by([.problem.benchCase.attributes.comparisonKey, .repeat])[]
    | (map(select(.arm == $control)) | first) as $c
    | (map(select(.arm == $candidate)) | first) as $k
    | select($c != null or $k != null)
    | arm($c) as $a | arm($k) as $b
    | {
        key: (($c // $k).problem.benchCase.attributes.comparisonKey), repeat: (($c // $k).repeat),
        sameHost: ($a != null and $b != null and $a.host == $b.host),
        control: $a, candidate: $b
      }
    | . + (
        if .control == null or .candidate == null then {class: "incomplete"}
        elif (.control.comparable and .candidate.comparable) | not then
          {class: "incomparable", reasons: [
            (if .control.comparable | not then "control-\(.control.stop // "missing")" else empty end),
            (if .candidate.comparable | not then "candidate-\(.candidate.stop // "missing")" else empty end)]}
        elif .control.prefixDigest != null and .control.prefixDigest == .candidate.prefixDigest then
          {class: "matched", elapsedRatio: ratio(.control.timeNs; .candidate.timeNs),
           cpuRatio: ratio(.control.cpuNs; .candidate.cpuNs)}
        elif .control.stop == "verdict" and .candidate.stop == "verdict" then
          {class: "completed-diverged", elapsedRatio: ratio(.control.timeNs; .candidate.timeNs)}
        else {class: "diverged"} + divergence(.control; .candidate)
        end)
    | del(.control.checkpoints, .candidate.checkpoints)
  ] as $rows
  | ($rows[]),
    {summary: true, control: $control, candidate: $candidate, rows: ($rows | length),
     matched: ([$rows[] | select(.class == "matched")] | length),
     completedDiverged: ([$rows[] | select(.class == "completed-diverged")] | length),
     diverged: ([$rows[] | select(.class == "diverged")] | length),
     incomparable: ([$rows[] | select(.class == "incomparable")] | length),
     incomplete: ([$rows[] | select(.class == "incomplete")] | length),
     crossHost: ([$rows[] | select(.class != "incomplete" and (.sameHost | not))] | length),
     matchedMedianElapsedRatio: ([$rows[] | select(.class == "matched") | .elapsedRatio | numbers] | median),
     matchedMedianCpuRatio: ([$rows[] | select(.class == "matched") | .cpuRatio | numbers] | median)}
'
