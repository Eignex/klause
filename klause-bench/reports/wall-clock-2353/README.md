# Subprocess proof timing

Lab job [786](http://192.168.50.104:8420/jobs/786) ran `hakank/building_a_house_model/building_a_house_model`
with Chuffed and cp-sat. Control: `58ae735e7b527a9d5e1050a0111b78f38b8590f0`;
candidate: `85cab2154863837a6d1c70a35154249cc08d6d02`. Both solver selections flatten to UNSAT
before invoking the solver, so this tests compiler proofs rather than reference search performance.

The queue ran eight alternating cases, serially on the Mac lab host, budget 5000 ms,
solver seed 3, two repeats per arm, result cache disabled. The lab builds installed CLI
binaries with `:klause-cli:installJvmDist`. The spec, resolved arm hashes, full raw records
and exact command log are retained beside this report. All eight proofs agreed.

| Arm | elapsedMs in repeats | timeToBestMs | solveTime |
| --- | --- | --- | --- |
| Control Chuffed | absent, absent | null | absent |
| Candidate Chuffed | 126, 120 | null | absent |
| Control cp-sat | absent, absent | null | absent |
| Candidate cp-sat | 117, 115 | null | absent |

Candidate CSV/offline comparison fallbacks therefore charge 115–126 ms instead of the
5000 ms budget, without inventing an incumbent timestamp or a solveTime statistic.
There is no solver speedup claim: this change retains an existing measurement.

The deployed lab's `Outcome.timeMs` and reference importer still fall back from solveTime
to budget; those external consumers need to read elapsedMs. This report reads the durable
records directly, and shared lab infrastructure was not edited.

Reproduce with `/home/rasmus/Workspaces/klause-lab/deploy/lab run flattening-spec.json`.
The pinned commits can be used without these report-only follow-up commits.
