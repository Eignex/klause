# Shared conflict coverage

`openAssertingConflicts` counts shared first-UIP analyses that produce an asserting backjump.
`openNonAssertingConflicts` counts sound resolvents retained with chronological fallback because the
shared analyzer cannot make them asserting. Root refutations, unusable explanations and native CP
conflict analysis do not enter either counter. Both counters add across feasibility rounds and
portfolio slices. They survive restarts and clause reductions.

A nonzero non-asserting count on a production corpus run is the trigger to capture the conflict and
inspect the component reasons it traverses. Zero does not establish that every factor has a reason;
it establishes that missing coverage did not prevent an asserting clause in that run.

## Acceptance protocol

`coverage-manifest.json` freezes the corpus before inspecting candidate outcomes. It includes the
three DTP cases from the earlier recount, seeded family controls across IDL/LIA/LRA/LIRA, and
DIMACS, OPB, WCNF, XCSP3 and MPS controls. `coverage-lab.json` runs each case in fresh JVMs against
the baseline and telemetry revision, alternating three repetitions on klause-lab. Presolve is off,
the engine is fixed, and both arms receive 50,000 committed decisions and a 10-second safety budget.

The telemetry change must preserve verdicts and deterministic counters whenever both arms finish
or spend the decision allowance. Runs stopped by wall time are timing observations, not equal-work
comparisons. Missing records and errors are failures. Check reference verdicts and compare runtime
over completed pairs; do not infer performance from new counters alone.

The improvement is that the coverage trigger is executable and archived in ordinary bench records.
If the trigger remains zero, close the dormant coverage investigation on that measured scope;
do not add order-atom proxies or unsupported reasons without a failing conflict.

## Recorded result

Lab [817](http://192.168.50.104:8420/experiments/817) completed all 567 cases: 63 inputs across nine
suites, three arms and three repetitions. The immutable revisions are baseline `58ae735e7`,
coverage `4ffba2d4e`, and the activity experiment's unchanged default path `84ec0f7f9`.
`coverage-default-control-lab.json` reproduces the full comparison. The first two arms each report
54 SAT, 38 UNSAT and 97 unknown records. The lab has reference coverage on 48 inputs and reports
no disagreements or optimization shortfalls.

The coverage arm emits 118,251 asserting and zero non-asserting conflicts across 186 records.
All records that emit shared `openWork` also emit both coverage fields. Three repetitions of
`CAV_2009_benchmarks/smt/10-vars/problem_2__030` terminate through a root LP Farkas certificate
before shared search: they emit no shared statistics and are excluded from the conflict count,
not silently interpreted as zero. The experimental default control emits 118,246 asserting and
zero non-asserting conflicts on the same scope.

All 103 coverage/baseline pairs where both complete or spend the decision allowance have identical
logical counters. Two verdict differences are opposite ten-second cutoff effects on
`planning/plan-13.cvc`: a completed run reports 1,931 open work units, while the stopped run reports
1,930. Each arm proves that input twice in three repeats. These pairs do not establish equal work.
The default control matches all 104 of its eligible pairs and proves that input in all three repeats.

The coverage arm's per-input geometric mean solve-time ratio on common completed inputs is 0.991.
The lab's completion-penalized ratio is 0.984, with 95% interval [0.960, 1.007]; this supports
neutral cost, not a speedup claim. The positive change is persistent coverage observability with
unchanged equal-work behavior. This recount does not justify additional component antecedents.

`coverage-results.csv` preserves every repetition's outcome, counters, runtime and binary
fingerprint. `coverage-results.json` includes matched comparisons and the raw cases payload's
SHA-256. Missing metrics remain explicit, with observed sums and record counts reported separately.
The lab stats and reference JSONs preserve intervals and external verdict checks.

After downloading the completed run's `/cases` and `/arms` JSON:

```sh
python3 klause-bench/learned-clauses/analyze.py cases.json \
  klause-bench/learned-clauses/coverage-manifest.json arms.json \
  --baseline baseline --output coverage-results
```
