# Paired lab record analysis

[analyze_lab.py](../klause-bench/tools/analyze_lab.py) reads a klause-lab case array
as JSON, gzip or xz and compares each arm against `--control`. It requires complete
blocks by input, solver seed and repetition. Missing blocks, error/unsupported
pairs, contradictory reported outcomes, mismatched source hashes and retained
objective mismatches are disclosed separately. Records without source hashes
remain explicitly unverified.

Reported witnesses, independently source-checked witnesses and proof claims have
different meanings. A retained witness's `_objective` matching the record checks
archive consistency; it does not independently recompute the source objective.
Source checking verifies witnesses, not optimality or refutation certificates.
Objective comparisons and witness consistency checks prefer canonical
`exactObjective` integer or rational text; legacy records use their numeric
`objective`. Authoritative `% klause-exact: _objective` comments take precedence
over rounded display assignments in retained FlatZinc witnesses. Summaries disclose
`sourceValidationScope`, separating MiniZinc source pinning from independent
original-FlatZinc checks with binary64 literal semantics. Approximate numeric
equality cannot tie distinct exact values.

Process incumbent clocks include subprocess launch, frontend loading, preparation,
search and output delivery. Legacy attribution clocks start during search.
Undecided cases receive twice their nominal budget in PAR2, while subprocess and
preparation duration summaries retain observed overshoot. Time to a run's own best
objective must be read alongside quality. Equal-outcome timing subsets are
descriptive, outcome-conditioned observations rather than causal estimates.

Family bootstrap intervals average seed/repetition observations within each catalog
base family, with the year removed. A single family has no estimated interval.
Counters report their native scope; see [presolve accounting](presolve.md).

Temporary manifests, raw records and reports belong in the shared
`klause-evidence/campaigns/<name>/` directory alongside the primary checkout.
The analyzer is a reusable tool; generated case bundles remain
outside the repository.

Portfolio exception diagnostics survive in the case record's `stats` map under
`armFailure.<label>`, using the CLI's bounded single-line JSON object. Inspect its
arm identity, failed phase, exception type/message and cause trace alongside the
arm schedule. A retained arm failure does not imply the whole command failed or
invalidate a sibling's verdict; quarantine faults have separate records.
