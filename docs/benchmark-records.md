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

Process incumbent clocks include subprocess launch, frontend loading, preparation,
search and output delivery. Legacy attribution clocks start during search.
Undecided cases receive twice their nominal budget in PAR2, while subprocess and
preparation duration summaries retain observed overshoot. Time to a run's own best
objective must be read alongside quality. Equal-outcome timing subsets are
descriptive, outcome-conditioned observations rather than causal estimates.

Family bootstrap intervals average seed/repetition observations within each catalog
base family, with the year removed. A single family has no estimated interval.
Counters report their native scope; see [presolve accounting](presolve.md).

The `build` workflow accepts `lab_cases`, `lab_control` and `lab_compression` on
manual dispatch. `lab_cases` is a base64-encoded gzip or xz case array. The analysis
job retains its decoded input and JSON report as the `lab-evidence` artifact and
runs no solver. Empty `lab_cases` runs the full build gate; pushes and pull requests
also run the full gate. Workflow input-size limits apply to the encoded payload.

Temporary manifests, raw records and reports belong in the shared
`klause-evidence/campaigns/<name>/` directory alongside the primary checkout.
The analyzer and workflow are reusable tools; generated case bundles remain
outside the repository.
