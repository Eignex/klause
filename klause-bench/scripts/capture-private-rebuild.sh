#!/usr/bin/env bash
set -euo pipefail
# Consume `lab cases ID --json`; preserve unknown lifecycle costs as null.
jq -c '
  (.record // {}) as $record | ($record.stats // {}) as $stats |
  ($record.privateRebuildLifecycle // {}) as $life |
  ["sourceUpdate", "mapping", "presolve", "workerOpen", "imports", "reconstruction",
   "sourceChecks", "coldWarmState", "publication", "retirement"] as $stages |
  {
    index, status, arm, seed, host,
    problem: .problem, revision: $record.gitSha,
    buildProvenance: $record.buildProvenance,
    resources: .problem.benchCase.resources,
    sourceInputs: .problem.benchCase.inputs,
    labCheck: $record.labCheck,
    outcome: {feasible: $record.feasible, proven: $record.proven,
              objective: $record.objective, witness: $record.finalWitness},
    observed: ($stats | with_entries(select(.key | test(
      "Rebuild|Inprocess|^presolve|^nativeCp|^arm\\.|[Mm]emory|[Rr]ss|[Rr]econstruct|[Cc]heck|[Tt]emplate|^solveTime|^frontend")))),
    lifecycle: {
      stageNs: ($stages | map(. as $stage | {key: $stage, value: $life.stageNs[$stage]}) | from_entries),
      totalNs: (if all($stages[]; . as $stage |
                  ($life.stageNs[$stage] | type) == "number" and $life.stageNs[$stage] >= 0)
                then [$stages[] as $stage | $life.stageNs[$stage]] | add else null end),
      peakOverlapOwnedPrimitiveBytes: $life.peakOverlapOwnedPrimitiveBytes,
      peakOverlapOwnedReferenceSlots: $life.peakOverlapOwnedReferenceSlots,
      protectedPrimitiveBytes: $life.protectedPrimitiveBytes,
      protectedReferenceSlots: $life.protectedReferenceSlots,
      obsoleteGenerationsReleased: $life.obsoleteGenerationsReleased,
      consumingWorkers: $life.consumingWorkers
    }
  }'
