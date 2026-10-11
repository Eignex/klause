# Numerical kernel decisions

These summaries support current implementation choices. The
[LP architecture](../lp/architecture.md), [certification](../lp/certification.md)
and [dependency integration](../lp/dependencies.md) pages own their contracts.
Measurements establish only the workloads, builds and runtimes stated here.
Raw records and campaign scripts follow the
[external evidence policy](../development.md#campaign-evidence).

## Dense basis selection

The floating basis owner selects dense LU for dimensions 2–6 at a selected-basis
nonzero density of at least 50%, with more nonzeros than rows. This is a bounded
selection, not a universal crossover. Direct captured factor-build evidence
favoring dense construction is at order six; orders 2–5 remain within the bounded
rule. Sparse construction handles the other bases and logical repair. The internal
`denseDimensionLimit = 0` control and `BasisSolver` factory support direct comparisons.

The selection was measured on real production-relaxation basis captures from
MIPLIB2017 `markshare1` (6 rows), `gr4x6` (34 rows) and `22433` (198 rows), plus the
committed MPS, MiniZinc and SMT basis corpus. Source `markshare1.mps` SHA-256 is
`7084187bf6377b664fd78949e597783872b5a40e8b2c2d3e6dec310678f46242`.
Its populated captured basis has 31 nonzeros out of 36; its initial unit basis
has six. The 22433 operation recording stops at the capture limit and establishes
only the recorded prefix.

Complete production factor builds were measured on Linux amd64, an Intel Xeon
Platinum 8488C and JDK 25.0.4.1, using the [pinned Koblas publication](../lp/dependencies.md#koblas).
Three fresh JVM forks each ran ten warmup batches and thirteen rotating measured
blocks of 20000 refactorizations per arm. The arms used full-buffer GER, windowed
GER and forced sparse construction. Retained-owner timing includes sparse ordering
reuse; fresh-owner timing includes setup and disposal. Both include numerical
guards and owned CSC factor publication. The JVM used one CPU, a fixed 1 GiB heap
and Serial GC; no installed vendor library was selected.

On the populated six-row basis, the windowed/full-buffer elapsed ratio was 0.983
for both retained and fresh owners. These estimates are geometric means of the
three fork medians of paired ratios. Hierarchical bootstrap 95% intervals were
0.971–0.993 and 0.975–0.992, respectively. The windowed/sparse ratios were 0.480
and 0.498. This is evidence from one captured populated basis, not a runtime-wide
speed guarantee. Separate JVM controls with the preceding Koblas binary gave
windowed-new/full-buffer-old ratios of 1.015 retained and 0.993 fresh; they do not
establish an overall dependency-upgrade speedup.

Five order-six windowed GER calls cover 55 entries, and measured deterministic
work is exactly 125 units lower per build than the full-buffer control's 180-entry
updates. All other checked work remains charged. Deterministic work units measure
visits rather than elapsed time. The six-row unit basis and all eleven captured
34/198-row bases made no dense attempt. Forced dense prototype controls on the
larger bases favor sparse construction; these controls do not justify enlarging
the selection.

Independent CSC products checked both solve directions outside factor timing.
Complete recorded operation replay checked factorization outcomes, update chains,
FTRAN/BTRAN residuals and fresh sparse reference residuals. The measurements use
Koblas's portable/SIMD BLAS composition.
A host-specific kernel win does not establish a complete-solver speedup or an
improvement on every supported runtime. The rule must not be enlarged based on
synthetic timing alone.

Matched source controls on Linux amd64 compared the full-buffer baseline with the
windowed implementation, using one processor, seed 1, a ten-second budget and
three repeats. The exact MPS selection comprised the six maintained MPS/component
fixtures plus `markshare1` and `22433`: all 24 paired blocks preserved source
hashes, acceptance policy, verdict, proof status and incumbents. The real-model
incumbents remained unproved. These budget outcomes do not establish new proofs.

A separate default-MPS selection forced the existing `lp-default` backtracking
arm on the three real MIPLIB models. All nine paired blocks matched incumbent and
proof status while exercising hundreds to thousands of node LP solves per run.
The exact and default policies are compared only within their respective pairs.
An independent rational parser checked all 54 final witnesses from the two
selections against original rows, bounds, integrality and objectives. Direct
enumeration checked the tiny optimum, and intersected singleton rows checked the
tiny contradiction (12 repeated proof checks across both arms). All six
default-policy `22433` cases remained unknown. Every case completed and retained
installed-build provenance. These controls establish preserved outcomes on the
selections, without a complete-solver timing-gain claim.

## Rational normalization

The [fraction factories](../../klause/src/commonMain/kotlin/com/eignex/klause/simplex/exact/BigFraction.kt)
avoid redundant GCD/division when coprimality is established. Exact normalization
and value preservation are defined in
[certification](../lp/certification.md#certification-mechanisms).

A [fixed-work build comparison](https://github.com/Eignex/klause/actions/runs/38043606727)
against general normalization covered six `nec-smt` inputs, three seeds, work
allowances 3000/6000 and three alternating pairs per seed/allowance. All 108
pairs spent the allowance and matched every non-timing counter and outcome.
Median paired duration reductions ranged from 7.41% to 15.53% across inputs;
seed/allowance block medians ranged from 2.78% to 16.55%.

[Independent source checks](https://github.com/Eignex/klause/actions/runs/38042314816)
covered 104 matched MPS/SMT records, including 24 finite optima, 16 infeasible
and 12 unknown results. Every claimed verdict agreed with source constraints.
Four default-MPS points were rounded rather than exactly feasible; their source
satisfiability was checked separately. Exact lowering refusals remained unsupported.

The evidence supports retaining these factory paths on the measured workloads.
Deadline controls traversed different prefixes and retained unknown outcomes;
they establish neither identical-work timing nor completed-solve gains.

## Cancellation polling

The [resource contract](../lp/certification.md#resources-and-cancellation) keeps
work-metered checks immediate, ordinary polling bounded and dynamic deadlines live.
Ordered compositions preserve construction-time deadline and first-meter snapshots.

A CPU profile attributed overlapping samples to cancellation calls in
reconstruction, LP wrappers and shared search. Such frame attribution does not
measure additive cost or establish savings from dispatch changes.
A [fixed-work dispatch comparison](https://github.com/Eignex/klause/actions/runs/38043602143)
covered three `nec-smt` inputs with five alternating pairs, seed 1 and work 3000.
Non-timing counters and unknown outcomes matched. Median paired duration
reductions were 0.45%, -0.57% and -0.20%, providing no useful dispatch-speedup
evidence. The implementation retains its dispatch and polling frequency.

Regression coverage protects live deadline rearming and external cancellation
through both public polling methods. Frame removal or inlining alone cannot
justify weakening that contract.
