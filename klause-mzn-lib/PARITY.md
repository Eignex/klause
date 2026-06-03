# MiniZinc parity testing

How we verify klause can run the full capability of MiniZinc with minimal
non-native decompositions.

## Goals

- Correctness parity: for every benchmark instance, klause's verdict
  (SAT / UNSAT / optimal value) matches a reference solver, Gecode by default.
- Native-coverage tracking: for every benchmark, count the FlatZinc constraint
  predicates that lower to klause-native factors versus the ones MiniZinc's
  standard library decomposes for us. The headline number is the average native
  coverage per source; the actionable list is the top-30 predicates that still
  fall back to decomposition.

The parity sweep uncovers two distinct kinds of gap. Coverage gaps are
predicates not listed in `share/minizinc/klause/redefinitions.mzn`, which fall
back to decomposition; adding them grows klause's native surface. Strength gaps
are predicates that are listed but where klause's LS or backtrack doesn't
reliably converge within budget; these show up as `KLAUSE_TIMEOUT` or
`KLAUSE_INFEASIBLE` verdicts even though parity is technically correct.

## Quick start

```bash
# One-time setup
./gradlew :klause-fzn-cli:installDist
./gradlew :klause-bench:downloadMzn          # ~250 MB, MiniZinc Challenge benchmarks

# Smoke (runs in CI; covers klause-mzn-lib/test-models/)
./gradlew :klause-bench:test --tests "com.eignex.klause.bench.parity.*"

# Full sweep over the Challenge benchmarks; writes build/parity-report.json
./gradlew :klause-bench:runMznParity \
    -Dklause.parity.source=mzn-bench \
    -Dklause.parity.timeoutSec=60 \
    -Dklause.parity.maxInstances=50
```

## Data sources

Each source has a discovery root and a Gradle task that populates it. All
datasets live under `klause-bench/build/mzn/` and are never committed. Pass any
comma-separated subset to `-Dklause.parity.source=…`.

- `smoke` (in-tree): the tiny curated set under `klause-mzn-lib/test-models/`,
  CI-gated.
- `mzn-bench` (`:klause-bench:downloadMzn`): the `MiniZinc/minizinc-benchmarks`
  Challenge corpus, around 133 problem dirs (~250 MB).
- `libminizinc-tests` (`:klause-bench:downloadMznTestSuite`): the
  `MiniZinc/libminizinc` compiler's own correctness test suite.
- `hakank` (`:klause-bench:downloadMznHakank`): the `hakank/hakank` collection,
  a large, stylistically varied model set (~200 MB).

## How it works

For each model and optional data pair:

1. Compile the model to FlatZinc against klause's solver definition:
   `minizinc --solver klause.msc -c -G <klause-mzn-lib>/share/minizinc/klause …`.
   The `-G` flag points MiniZinc at klause's redefinitions library so native
   predicates bind first; the rest fall back to MiniZinc's standard library and
   decompose.
2. Parse the resulting `.fzn` for constraint-predicate counts
   (`constraint <name>(…)`). Bucket each into `nativeUsed` (listed in
   `redefinitions.mzn`) versus `decomposedUsed`.
3. Solve the instance twice through `minizinc`: once with `--solver klause.msc`,
   which exercises the production end-to-end path, and once with
   `--solver org.gecode.gecode` for the reference verdict.
4. Compare verdicts and emit one of the verdicts below.

The verdicts are:

- `OK`: klause and reference agree on sat/unsat, and on the optimum if relevant.
- `SAT_DISAGREEMENT`: one says SAT, the other UNSAT — a real correctness bug.
- `KLAUSE_INFEASIBLE`: the reference found a solution; klause did not within budget.
- `OPT_VALUE_MISMATCH`: both found solutions but the optimums differ.
- `KLAUSE_TIMEOUT`: klause exceeded the per-instance budget.
- `COMPILE_ERROR`: `minizinc -c` failed to lower the model against klause.
- `REFERENCE_UNAVAILABLE`: Gecode (or the chosen reference) is missing on PATH.

The report aggregates these per instance plus a single `decomposedTopHits` map
showing the highest-leverage predicates still being decomposed across the sweep.

## Knobs

System properties read by `MznParitySweepMain`:

- `klause.parity.source` — comma-separated, default `smoke`
- `klause.parity.timeoutSec` — per-instance solver budget, default 30
- `klause.parity.maxInstances` — cap per source, default unbounded
- `klause.parity.report` — output path, default `klause-bench/build/parity-report.json`
- `klause.parity.failOnNonOk` — exit non-zero if any instance isn't `OK`, default false

Smoke-test-only knob:

- `klause.parity.smoke.strict` — fail the smoke on allow-listed timeouts too

## CI integration

The smoke test (`MznParitySmokeTest`) runs as part of the regular
`:klause-bench:test` task. It silently skips when `minizinc` isn't on PATH or
`klause-fzn-cli` hasn't been installed, so it tolerates bare CI images. When the
toolchain is present, it fails on real correctness deviations
(`SAT_DISAGREEMENT`, `OPT_VALUE_MISMATCH`, `COMPILE_ERROR`, and so on); allows
known LS-strength or engine-bug instances on a small `KNOWN_LS_DIFFICULT`
allow-list (`magic_square`, `zero_one_knapsack`), surfacing them in the build log
instead of failing; and re-enables strict mode under
`-Dklause.parity.smoke.strict=true`.

The full sweep is for offline runs and tightening campaigns. Use it before
bumping the native-predicate set, when adding a global, or to chase down a
specific decomposition hot spot.

## Workflow for closing coverage gaps

1. Run the sweep over `mzn-bench` or a focused subset.
2. Open `build/parity-report.json` and look at `aggregate.decomposedTopHits`.
   The top entries are the predicates with the most leverage to make native.
3. For each candidate, add a factor, AST, DSL, and compiler-lowering path in
   klause core; wire a `predicate <name>(…);` declaration in
   `share/minizinc/klause/redefinitions.mzn` so MiniZinc stops decomposing it;
   and add per-predicate unit and parity coverage tests.
4. Re-run the sweep; `nativeCoverage` should rise.
