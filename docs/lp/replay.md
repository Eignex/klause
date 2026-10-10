# LP capture and replay

## Basis corpus

The `:klause:basisTrace` task operates on the committed, versioned basis corpus under
`klause/src/jvmTest/resources/basis-corpus/`. It runs only when invoked explicitly; ordinary tests never perform
capture or timing.


```
./gradlew :klause:basisTrace --args="list"
./gradlew :klause:basisTrace --args="verify"
./gradlew :klause:basisTrace --args="replay"
./gradlew :klause:basisTrace --args="benchmark"
./gradlew :klause:basisTrace --args="replay trace=/path/to/one.kbtrace"
```

`list`, `replay`, `benchmark`, and `verify` use committed fixtures by default and emit one NDJSON record per
fixture (or one corpus-verification record). Replay rebuilds portable headings on the basis under test,
recomputes update preparation on its own current factors, and checks `B*x=b` and `B^T*x=b` directly against the
stored CSC matrix after builds and updates. A replay stops at the failing operation and resumes at the next
complete checkpoint.

Two oracles are independent of the basis under test. The stored CSC matrix and the tracked headings give the
residual check. The captured trace supplies the factorization outcome and the update acceptance the real solve
observed. Both are checked before anything else, so nothing downstream can mask them.

Replay records the koblas jar hash, kernel selection, and resolved vendor library/version. The selection
identifies the engine composition, not the implementation of every operation: short and strided Level 1
calls can use portable kernels. The sparse basis does not call vendor Level 2/3. Run with
`-Pkoblas.noSimd=true` to check the portable JVM reductions against the same captured observations.

Every accepted update is then rebuilt by a from-scratch Markowitz factorization of the same columns and solved
through the rebuild. This is a measurement, not a third oracle: it runs only on a basis the residual check has
already accepted and probes the same right-hand side, so it cannot find an unsolvable basis that check missed.
It reports `freshRelativeResidual`, the accuracy a from-scratch build reaches on that probe and so a drift
reference for the Forrest-Tomlin chain, and `freshDeclines`, a conditioning signal. A declining rebuild is
reported rather than failed: it applies threshold partial pivoting under a bounded Markowitz search while an
update accepts on an absolute pivot test alone, so the two disagree on badly scaled bases by construction.

`benchmark` performs one warmup and three repetitions. Setup, build/rebuild, FTRAN, BTRAN, update-only,
preparation-plus-update, composed lifecycle and setup-plus-lifecycle totals are reported separately with
median/min/max nanoseconds; setup, individual operations and preparation-plus-update also report Java thread
allocations. The oracle checks are outside the timed regions. Preparation-plus-update is labelled
`synthetic_prepared`: a shadow basis recomputes the referenced solves and applies the update, while shadow setup
and checkpoint builds stay outside that metric and the composed lifecycle.

`compare` replays one complete `mps-afiro` operation window under its captured rebuilds and a fixed
deferred schedule: skip the successful same-heading checkpoint at operation 41, rebuild at the next
captured checkpoint at 86, and continue through operation 93. It runs one warmup and four alternating
pairs; `trace=... checkpoint=...` selects another complete trace and validated same-heading checkpoint with a later build
and following solve. A same-heading checkpoint has the identical immutable matrix and ordered basis;
the trace does not record the production reason for rebuilding, so this is a basis-level cost experiment,
not authorization to change solver policy. Any changed/failed/initial checkpoint, unexpected backend
rebuild advice, numerical decline or 16-update harness limit invalidates that pair. The backend's own
50-update limit remains in force. Both arms receive the same ordered trace operations and RHS vectors,
but each computes its own spike and pivotal row. Cancellation is checked between operations in both arms.

The comparator checks each update state in a separate validation pass and checks every measured solve output
after replay against the stored CSC matrix and a fresh factorization of its recorded headings. This keeps
oracle probes out of measured backend work. Timed phases cover owner
setup, every captured build, RHS preparation and solves, updates, and close. NDJSON records phase and total
elapsed time, current-thread CPU, Java allocations, plus independent backend work units. Native allocations
are unavailable. Validity requires both arms to complete the whole window with independent numerical checks;
captured update labels do not prove the deferred arm. These operation traces contain no complete LP bounds,
objective, source proof or exact certificate, so they cannot establish an exact solver outcome. The fixed
40-boxed-integer full-solver regression remains a separate control for any later policy proposal.

```
./gradlew :klause:basisTrace --args="compare"
```

Repair and structural replacement are covered by the dedicated engine tests rather than by replay, which drives
only factorization, FTRAN, BTRAN and updates.

Capture is opt-in and bounded to the limits in `basis-corpus/manifest.json`. It records raw matrix/RHS IEEE bits,
source/unit headings, source mappings, strictness, update evidence and checkpoints; it never stores LU state or
native pointers. Examples for every supported source format:

```
./gradlew :klause:basisTrace --args="capture id=local-mps format=mps source=/corpus/model.mps source-label=collection/model.mps@REV license=LICENSE output=/path/to/evidence/local-mps.kbtrace"
./gradlew :klause:basisTrace --args="capture id=local-mzn format=minizinc source=klause-mzn-lib/test-models/graph_coloring.mzn license=repository-internal output=/path/to/evidence/local-mzn.kbtrace"
./gradlew :klause:basisTrace --args="capture id=local-smt format=smtlib source=klause-bench/smoke-corpus/smtlib/lia-wide-span.smt2 license=repository-internal output=/path/to/evidence/local-smt.kbtrace"
```

MiniZinc source is compiled through this repository's `klause.msc`; an already flattened `.fzn` is accepted
directly. MPS and MiniZinc traces use the production LP-relaxation assembly. SMT traces are labelled
`SMT_SOURCE_DERIVED`: the frontend's authoritative exact asserted state is projected into the float engine for
this harness. This capture route is distinct from a production theory event stream. Boolean SMT branches and objectives
decline instead of being silently selected or relaxed. See [fixture provenance](../testing/fixtures.md) for source identities,
redistribution terms, hashes and exclusions.

## CP LP owner lifecycle comparison

`klause-bench/scripts/lp-lifecycle/` contains an opt-in retained-versus-fresh comparator for a captured CP
consumer trace. It compiles against `:klause`'s internal JVM test API through its init script;
ordinary bench runs and tests do not load it. The source is the two-integer problem
`x,y ∈ [-3,7]`, `x+y >= 1`, minimizing `2x+y+5`. One root and four
push/push/pop/push/pop cycles must reproduce the frozen 21-state capture SHA-256
`deb7c8bb9b3c744e71b31893b7abd1c1f1b8511d8edeecda8f965566e6d4c648`.

```
./gradlew -I klause-bench/scripts/lp-lifecycle/lp-lifecycle.init.gradle :klause:lpLifecycleComparison --max-workers=2 > /tmp/lp-lifecycle.log
python3 klause-bench/scripts/lp-lifecycle/check_lifecycle.py /tmp/lp-lifecycle.log > /tmp/lp-lifecycle-summary.json
python3 -m unittest discover -s klause-bench/scripts/lp-lifecycle -p 'test_*.py'
```

The retained arm builds one original `Problem`, CP session and LP engine, then applies each bound
decision or pop to that session. The fresh arm starts from the same original `Problem` for each state,
replays that state's source decisions, builds its relaxation through `nodeRelaxation`, solves through
`solveNode`, certifies directly and closes the engine. Both arms pay for source assembly through the
same consumer methods. The recorded phases are setup, CP transition or reconstruction, relaxation
assembly, `solveNode`, direct certification and disposal. `solveNode` includes projection/import and
float solve; its subphases have no separate external timing seam. Model fingerprinting, output and
independent checking are outside phase timing. The checker verifies every original-coordinate exact
witness, objective constant and lower bound with a vertex oracle, plus state/model equality, paired
counts and closed owners. It rejects missing records rather than turning them into zero work.

Cold setup and transition include constructing a new CP session and replaying its decision prefix;
retained setup and transition update the live session. The complete total therefore measures that
whole-consumer reconstruction policy. It does not isolate the benefit of LP basis retention. Direct
`certifyLpResult` measures source proof without the rest of `lpBoundAndFix` (pruning, cuts and
explanations), so this diagnostic does not represent a full search run.

The comparison factory fixes the effective LP work allowance at 1,000,000 for both arms (including
retained `resolveBounds`) while preserving the production solver route, refactor cap, pricing and
cancellation. The factory reports requested pre-normalization limits too. This is a bounded diagnostic
configuration, not a production policy change. Java current-thread allocation counts exclude native
allocation and may be unavailable. Elapsed totals are paired in alternating arm order with one warmup;
they are descriptive on a shared host. The committed `.kbtrace` corpus records basis operations, not
complete CP consumer states, so it cannot substitute for this lifecycle comparison. Full search performance requires separate consumer measurements.

## Dense selection comparison

```
./gradlew :klause:basisTrace --args="compare-dense trace=/path/to/captured.kbtrace repetitions=5"
```

`compare-dense` runs one warmup per arm and alternating pairs of the whole recorded
operation stream. `sparse` sets `denseDimensionLimit = 0`; `selected` uses the
production dimension/density rule. Each record includes the pair/order, source and
artifact hashes, runtime and Koblas identity, dense build attempts, phase timing,
Java allocation counts, update observations and independent source residuals in both
directions. The fresh numerical reference always disables dense selection. Any
state/residual error stops the comparison. A truncated trace establishes only its
recorded prefix. Short trace timings include JIT/noise and do not establish a
complete-solver gain or a universal crossover. Matched source solves and repeated
factor batches are separate controls.
