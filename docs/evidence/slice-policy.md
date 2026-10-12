# Portfolio slice policy

Retain the finite schedule's base 5000, maximum 150000 and growth 1.5. The comparison
found workload-specific benefits from alternatives without a general replacement.
[Portfolio scheduling](../solver/portfolio.md#slice-accounting-and-coverage) and
[resources](../solver/resources.md) own the policy and work currencies.

## Scope and controls

The comparison covered 13 inputs across MiniZinc, XCSP3, MPS, DIMACS, WCNF and OPB:
312 runs with two seeds and two repeats per setting. Settings were implicit/explicit
defaults, base 1000, maximum 30000, growth 2 and a flat 5000 schedule. Each run used
one processor, a 200000 decision/move allowance and a 30-second outer deadline.
Source/build identities and effective settings were checked. Equal nominal caps
did not imply equal completed work when preparation or deadlines stopped a run.

## Decision evidence

The smaller base reduced work to proofs on `flugpl` and `hole9`, but produced no
witness on Cargo `challenge05_1s_954`, where defaults produced checked cost-11032
witnesses at the same ledger charge. Growth 2 found checked cost-6 witnesses on
`GenHyperTW_grid5`; scheduling/allocation inputs did not show a general advantage.
The flat and smaller-maximum schedules likewise gave no consistent benefit.

These tradeoffs support the retained defaults on this selection. They establish
neither a globally best schedule nor deterministic outcomes under elapsed limits.

## Acceptance and limits

Independent original-source checks under the recorded policies accepted 229 final
witnesses and checked all
72 completed proof claims through source refutation or better-objective exclusion.
Witness acceptance and proof credit remain separate. A further 52 one-/four-processor
runs stayed within the aggregate 20000-unit cap while retaining unused worker quota;
that check was not a matched-work speed comparison.

[Review evidence](https://github.com/Eignex/klause-lab/releases/tag/active-evidence-resource-scopes-slice-policy)
records specifications, build identities and source checks for revision
`fdf42c72c1e4eda1d9e4d421fb84fd0cd53318c3`. Its snapshot excludes source input bytes,
installed binaries, profiles and original subprocess stdout. Conclusions are limited
to the recorded selection and runtime.
