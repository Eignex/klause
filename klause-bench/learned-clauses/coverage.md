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
