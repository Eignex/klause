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

## Repeated acceptance and related controls

[AWS 869](http://192.168.50.104:8420/experiments/869) completed all 18 paired route cases on one
exclusive c7i.2xlarge, without JFR. Every process exited zero. Its main binary fingerprint is
`c5f3896bdc0ec8fd17986a96333426c23c7369322641a064d6e0e9fb8f35e566`; the executed fix fingerprint
is `56cd42900857db7ceeaf18c03a20f83ddf69054a08a39cc56e71fcabece3d4a7`. Raw provenance maps
retain each installed jar, launcher and Java runtime hash. The executed fix is `029713ddf`; the
evidence layer changes only reports and their CI audits, not solver source.

| Route | Main outcomes / nodes / failures | Fix outcomes / nodes / failures | Main solve seconds | Fix solve seconds |
| --- | --- | --- | --- | --- |
| conflictDriven | UNKNOWN × 3 / 873 / 70782, 70456, 63962 | SAT × 3 / 1813 / 1194 | 59.024 / 59.010 / 59.032 | 2.917 / 2.810 / 2.845 |
| satOptimized | SAT × 3 / 416 / 68 | SAT × 3 / 416 / 68 | 0.529 / 0.528 / 0.504 | 0.518 / 0.489 / 0.524 |
| default, one arm, LP off | SAT × 3 / 416 / 68 | SAT × 3 / 416 / 68 | 0.507 / 0.516 / 0.496 | 0.501 / 0.499 / 0.510 |

This establishes recovery under the issue's node/deadline allowance, not a fixed-work speedup:
main hits the wall while the fix finishes. Passing-route times are small and mixed; no throughput
improvement is claimed. The lab reference covers RWS and reports no disagreements or shortfalls.

[AWS 870](http://192.168.50.104:8420/experiments/870) completed all 48 related-control cases:
eight inputs across SMT-LIB, DIMACS, OPB, XCSP3 and WCNF, two revisions, seed 1 and three repeats.
It uses an exclusive serial c7i.2xlarge, LP off, node allowance 10,000 and wall allowance 10,000 ms.
Every process exited zero. All 24 matched pairs preserve verdict, proof flag, objective, nodes and
failures. `RWS/Example_1.txt` remains SAT at 1,835 nodes/950 failures; `harp2` remains UNKNOWN at
exactly 10,000 nodes/91 failures. The six small format controls retain SAT/UNSAT results and the
set-cover optimum 4 / MaxSAT optimum 1. The lab reference covers only the two SMT inputs and reports
no disagreement or shortfall; this is not eight independently checked source witnesses. Most tiny
controls require little or no search, so this bounded sample does not establish broad performance.

The default route in [historical frozen 764](http://192.168.50.104:8420/experiments/764) also left LP
and arm defaults unrestricted. [AWS 877](http://192.168.50.104:8420/experiments/877) preserves that
distinct CP route under the original ten-second wall / 100,000-node control. All six cases finished
with zero process exits and SAT at 416 nodes/68 failures. Main solve seconds are 0.539 / 0.474 /
0.482; fix seconds are 0.505 / 0.485 / 0.476. Both revisions use the same binary fingerprints as
869, and reference comparison reports no disagreements or shortfalls. These three paired repeats
preserve the unrestricted default route separately from the LP-off one-arm acceptance.

SAT records use `reported-result-v1`, not an independent source-witness checker. Reference agreement
and a returned SAT model must not be described as independently verified witnesses. No source model,
certificate or optimum is inferred from an UNKNOWN. Temporary local tracing and its times are
diagnostics only; the execution-policy history is recorded in README.md.

## CI and stack

[#2372](https://github.com/Eignex/klause/pull/2372) freezes reproduction;
[#2377](https://github.com/Eignex/klause/pull/2377) changes Boolean pin lifetime and adds the regression;
[#2385](https://github.com/Eignex/klause/pull/2385) archives the measurements and audits them in
GitHub CI. The full local gate and local unit tests were skipped. GitHub JVM/native/lint/docs checks
passed on reproducer `b080b243f` and fix `029713ddf`; their retained CI metadata links exact runs.
The CI control branch `f75cdae05cf95cdd60fb4dfef4b60a392db00b35` retains the added test with the
old assignment behavior; [its CI run](https://github.com/Eignex/klause/actions/runs/37989439563)
failed on that regression on JVM with `expected:<false> but was:<null>` in 5 ms. The candidate passes
the same test in less than the report's 1 ms resolution. Both class reports are retained and checked
by the evidence audit. The redundant native control was cancelled after the JVM failure proved the
regression; this does not cancel or replace any stack PR gate. The evidence layer's build and audit
workflows run on each update; check its latest head through the PR's checks.

The reproducer merged into main as `bc001bb90a10df3905cc04a6b009b92f1e956152`. The fix was
rebased onto that commit as `4b8baad714d19ce5d144c3b3320f34984b5c08ed`; its entire Git tree
is identical to the executed candidate's tree (`b209e2e169b693b6d4e7138bdc3db7f8edaf58ed`).
This changes commit ancestry without changing the measured source or test. Fresh CI gates are
required on the rebased PR head. The evidence layer remains a separate incremental report/audit diff.

The lab HTTP records remain available. A separate `deploy/lab ssh 869 0` read attempted to check the
AWS input hash during setup, but local SSH authentication to the lab server was denied (including
a retry restricted to the existing identity). No solve ran through that failed read. The input hash
in README.md identifies the local cached source inspected before the remote-only policy, rather
than an independently rehashed AWS file. The remote records identify the executed corpus path and
the frozen Zenodo collection; binary/runtime provenance is independently captured by the runner.
