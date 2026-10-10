# Dense basis selection

The floating basis owner selects dense LU for dimensions 2–6 at a selected-basis
nonzero density of at least 50%, with more nonzeros than rows. This is a conservative measured envelope, not a
universal crossover. Sparse construction handles the other bases and logical
repair. The internal `denseDimensionLimit = 0` control and `BasisSolver` factory
support direct comparisons.

The selection was measured on real production-relaxation basis captures from
MIPLIB2017 `markshare1` (6 rows), `gr4x6` (34 rows) and `22433` (198 rows), plus the
committed MPS, MiniZinc and SMT basis corpus. Source `markshare1.mps` SHA-256 is
`7084187bf6377b664fd78949e597783872b5a40e8b2c2d3e6dec310678f46242`.
Its populated captured basis has 31 nonzeros out of 36; its initial unit basis
has six. The 22433 operation recording stops at the capture limit and establishes
only the recorded prefix.

On Linux amd64, JDK 25.0.1 and the pinned Koblas JVM artifact
`cf9486a34f4a1797ce477109594c3fb3858e8eebea7ecbc30ea263de53a8895d`,
the guarded dense prototype favored the populated six-row basis. The 34/198-row
captured bases favored sparse construction. Seven alternating production pairs,
after warmup, compared 2000 six-row refactorizations per arm and 100 larger
refactorizations per arm. The selected path's median paired elapsed ratio on the
populated six-row basis was 0.64 for retained owners (including sparse ordering
reuse), and 0.53 for fresh setup, factorization and disposal. Those measurements
include owned sparse factor materialization. A separate repeat gave ratios 0.71
and 0.53, respectively. Larger bases and the six-row unit
basis made no dense attempt. Deterministic work units measure visits rather than
elapsed time; dense work is charged even when wall time is lower.

Independent CSC products checked both solve directions outside factor timing.
Complete recorded operation replay checked factorization outcomes, update chains,
FTRAN/BTRAN residuals and fresh sparse reference residuals. The measurements use
Koblas's portable/SIMD BLAS composition; no installed vendor library was selected.
A host-specific kernel win does not establish a complete-solver speedup or an
improvement on every supported runtime. The rule must not be enlarged based on
synthetic timing alone.

Matched source controls on macOS arm64 compared pinned sparse main
`8a270e0a` with selected-path revision `86008bdc`, using one processor, seed 1,
a ten-second budget and five repeats. The exact MPS selection comprised the six
maintained MPS/component fixtures plus `markshare1` and `22433`: all 40 paired
blocks preserved source hashes, acceptance policy, verdict and proof status, with
no worse incumbent or changed proven objective. `markshare1` remained unproved;
one selected repeat improved its incumbent from 119 to 88. Both arms left `22433`
unknown. These budget outcomes do not establish new proofs.

A separate default-MPS selection forced the existing `lp-default` backtracking
arm on the three real MIPLIB models. All 15 paired blocks matched incumbent and
proof status while exercising hundreds to thousands of node LP solves per run.
The exact and default policies are compared only within their respective pairs.
An independent rational parser checked all 90 final witnesses from the two
selections against original rows, bounds, integrality and objectives. Direct
enumeration checked the tiny optimum, and intersected singleton rows checked the
tiny contradiction (20 repeated proof checks across both arms). Every case
completed and retained installed-build provenance; failed worker setup attempts
were excluded. These controls establish preserved outcomes on the selections,
without a complete-solver timing-gain claim.
