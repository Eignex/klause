# RWS Boolean pin lifetime

The native CP analyzer used the effective reason level of a Boolean pin even when a deeper undo mark
owned the pin. A mixed learned clause can cite an old integer bound and force a Boolean during a
deeper decision. Its lower reason level does not make the Boolean survive undo to that level.
An asserting backjump could consequently erase several clause literals and resume without a unit
implication. Repeating the same conflict added clauses but made no committed search progress.

The fix dates Boolean assignments at the deeper of the factor's effective level and the current
decision count. This preserves their real undo lifetime while leaving the clause reasons available
to resolution. It changes no portfolio setup, LP policy or numerical parameter. The small behavioral
regression adds a mixed clause after an unrelated deeper integer decision, triggers a Boolean
conflict, applies its learned backjump and requires the asserting Boolean consequence.

## Current-main baseline

[AWS 852](http://192.168.50.104:8420/experiments/852) ran all 15 cases serially on one c7i.2xlarge,
without JFR, with seed 1, LP off, one processor, node allowance 100,000 and wall allowance 60,000 ms.
Every process exited zero. Baseline/candidate timings from different jobs are not paired comparisons.
The raw `baseline-{cases,arms,job}.json` and job log retain the exact commands, build/runtime hashes,
per-case execution times and all counters. The default route retains the original one-arm selection.

| Route/revision | Outcomes | Nodes per repeat | Failures per repeat | Solve seconds |
| --- | --- | --- | --- | --- |
| Main `5471419aa`, conflictDriven | UNKNOWN × 3 | 873 / 873 / 873 | 66556 / 71680 / 66006 | 58.974 / 59.031 / 59.056 |
| Historical regression `7cbf3ba14`, conflictDriven | UNKNOWN × 3 | 873 / 873 / 873 | 65096 / 68233 / 72223 | 58.975 / 59.032 / 59.036 |
| Historical control `86156e3e2`, conflictDriven | SAT × 3 | 2034 / 2034 / 2034 | 1727 / 1727 / 1727 | 4.660 / 4.779 / 4.626 |
| Main, satOptimized | SAT × 3 | 416 / 416 / 416 | 68 / 68 / 68 | 0.493 / 0.513 / 0.486 |
| Main, default | SAT × 3 | 416 / 416 / 416 | 68 / 68 / 68 | 0.476 / 0.505 / 0.470 |

All routes use finite CP. Main emits zero shared asserting/non-asserting conflicts and zero open
work; those counters from #2364 do not describe the native analyzer's repeated conflicts here.
Wall-limited UNKNOWNs did not spend the node allowance, so this is a deadline outcome and progress
regression, not fixed-work throughput evidence. Solve seconds exclude some process setup; the exact
CLI deadline also covers routing/presolve. Process elapsed times remain separate in the raw records.

## Candidate canary

[AWS 857](http://192.168.50.104:8420/experiments/857) compares unchanged main with executed candidate
`029713ddf9736782227cdc09f1d2a9eab6f75698` on one exclusive instance, with the same controls and
one repetition. Main is UNKNOWN at 873 nodes/66,449 failures in 58.882 solve seconds; the candidate
is SAT at 1,813 nodes/1,194 failures in 2.775 solve seconds. The canary specification used the
candidate's unique nine-character commit abbreviation; its arm metadata and records freeze the full
resolved SHA and executed binary fingerprint. This one repetition establishes recovery in the
canary, not repeated acceptance or a general speedup.

Repeated route acceptance and eight bounded related-format controls are pending. Their specifications
are retained separately. The related controls use three repetitions, node allowance 10,000 and wall
allowance 10,000 ms. Candidate/default and candidate/satOptimized preservation remain required.

SAT records use `reported-result-v1`, not an independent source-witness checker. Reference agreement
and a returned SAT model must not be described as independently verified witnesses. No source model,
certificate or optimum is inferred from an UNKNOWN. Temporary local tracing and its times are
diagnostics only; the execution-policy history is recorded in README.md.

## CI and stack

[#2372](https://github.com/Eignex/klause/pull/2372) freezes reproduction;
[#2377](https://github.com/Eignex/klause/pull/2377) changes Boolean pin lifetime and adds the regression;
the dependent evidence layer archives the measurements and audits them in GitHub CI. The full local
gate and local unit tests were skipped. GitHub JVM/native/lint/docs checks are pending on the fix.
The CI control branch `f75cdae05cf95cdd60fb4dfef4b60a392db00b35` retains the added test with the
old assignment behavior; [its CI run](https://github.com/Eignex/klause/actions/runs/37989439563) is
expected to fail that regression and is pending. No stack PR is reported green at this checkpoint.
