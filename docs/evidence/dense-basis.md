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

Matched exact MPS source controls used pinned sparse main and the selected path,
one processor, seed 1, a ten-second budget and five repeats. All 30 paired blocks
on the six maintained MPS/component fixtures matched source hashes, acceptance
policy, verdict, proof status and exact objective. An independent rational parser
checked 50 final witnesses against original rows, bounds and integrality; direct
enumeration checked the tiny objective optimum, and intersected singleton rows
checked the tiny contradiction (20 repeated proof checks across both arms).
Installed-build hashes remain in the external case records. These controls
establish preserved outcomes on that selection, without a timing-gain claim.
