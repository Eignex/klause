# MiniZinc parity testing

How we verify klause can run the full capability of MiniZinc with minimal non-native
decompositions.

## Goals

1. **Correctness parity** — for every benchmark instance, klause's verdict (SAT / UNSAT /
   optimal value) matches a reference solver (Gecode by default).
2. **Native-coverage tracking** — for every benchmark, count the FlatZinc constraint
   predicates that lower to klause-native factors vs the ones MiniZinc's standard library
   decomposes for us. The headline number is "average native coverage" per source; the
   actionable list is the top-30 predicates that still fall back to decomposition.

The parity sweep uncovers two distinct kinds of gap:

- **Coverage gaps** — predicates not listed in `share/minizinc/klause/redefinitions.mzn`
  fall back to decomposition. Adding them grows klause's native surface.
- **Strength gaps** — predicates that *are* listed but where klause's LS / backtrack
  doesn't reliably converge within budget. These show up as `KLAUSE_TIMEOUT` /
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

Discovery roots and the Gradle task that populates each. All datasets live under
`klause-bench/build/mzn/` and are never committed.

| source id          | task                                          | what it covers                                                                 |
|--------------------|-----------------------------------------------|--------------------------------------------------------------------------------|
| `smoke`            | (in-tree)                                     | `klause-mzn-lib/test-models/` — tiny curated set, CI-gated                     |
| `mzn-bench`        | `:klause-bench:downloadMzn`                   | `MiniZinc/minizinc-benchmarks` — Challenge corpus, ~133 problem dirs (~250 MB) |
| `libminizinc-tests`| `:klause-bench:downloadMznTestSuite`          | `MiniZinc/libminizinc` — compiler's own correctness test suite                 |
| `hakank`           | `:klause-bench:downloadMznHakank`             | `hakank/hakank` — large stylistically-varied model collection (~200 MB)        |

Pass any comma-separated subset to `-Dklause.parity.source=…`.

## How it works

For each `(model, optional data)` pair:

1. Compile the model to FlatZinc against klause's solver definition:
   `minizinc --solver klause.msc -c -G <klause-mzn-lib>/share/minizinc/klause …`
   The `-G` flag points MiniZinc at klause's redefinitions library so native predicates
   bind first; the rest fall back to MiniZinc's standard library and decompose.
2. Parse the resulting `.fzn` for constraint-predicate counts (`constraint <name>(…)`).
   Bucket each into `nativeUsed` (listed in `redefinitions.mzn`) vs `decomposedUsed`.
3. Solve the instance twice through `minizinc`:
   - Once with `--solver klause.msc` — exercises the production end-to-end path.
   - Once with `--solver org.gecode.gecode` — the reference verdict.
4. Compare verdicts and emit a `Verdict`:

   | Verdict                  | Meaning                                                            |
   |--------------------------|--------------------------------------------------------------------|
   | `OK`                     | klause and reference agree (sat/unsat, and on optimum if relevant) |
   | `SAT_DISAGREEMENT`       | one says SAT, the other UNSAT — real correctness bug               |
   | `KLAUSE_INFEASIBLE`      | reference found a solution; klause did not within budget           |
   | `OPT_VALUE_MISMATCH`     | both found solutions but optimums differ                           |
   | `KLAUSE_TIMEOUT`         | klause exceeded the per-instance budget                            |
   | `COMPILE_ERROR`          | `minizinc -c` failed to lower the model against klause             |
   | `REFERENCE_UNAVAILABLE`  | gecode (or chosen reference) missing on PATH                       |

The report aggregates these per instance plus a single `decomposedTopHits` map showing
the highest-leverage predicates still being decomposed across the sweep.

## Knobs

System properties read by `MznParitySweepMain`:

- `klause.parity.source` — comma-separated, default `smoke`
- `klause.parity.timeoutSec` — per-instance solver budget, default 30
- `klause.parity.maxInstances` — cap per source, default unbounded
- `klause.parity.report` — output path, default `klause-bench/build/parity-report.json`
- `klause.parity.failOnNonOk` — exit non-zero if any instance isn't `OK`, default false

Smoke-test-only knobs:

- `klause.parity.smoke.strict` — fail the smoke on allow-listed timeouts too

## CI integration

The smoke test (`MznParitySmokeTest`) runs as part of the regular `:klause-bench:test`
task. It silently skips when `minizinc` isn't on PATH or `klause-fzn-cli` hasn't been
installed, so it tolerates bare CI images. When the toolchain is present, it:

- Fails on real correctness deviations (`SAT_DISAGREEMENT`, `OPT_VALUE_MISMATCH`,
  `COMPILE_ERROR`, …).
- Allows known LS-strength / engine-bug instances on a small `KNOWN_LS_DIFFICULT`
  allow-list (`magic_square`, `zero_one_knapsack`), surfacing them in the build log
  instead of failing.
- Re-enables strict mode under `-Dklause.parity.smoke.strict=true`.

The full sweep is for offline runs and tightening campaigns. Use it before bumping the
native-predicate set, when adding a global, or to chase down a specific decomposition
hot spot.

## Workflow for closing coverage gaps

1. Run the sweep over `mzn-bench` (or a focused subset).
2. Open `build/parity-report.json` and look at `aggregate.decomposedTopHits`. The top
   entries are the predicates with the most leverage to make native.
3. For each candidate:
   - Add a factor + AST + DSL + compiler-lowering path in klause core.
   - Wire a `predicate <name>(…);` declaration in `share/minizinc/klause/redefinitions.mzn`
     so MiniZinc stops decomposing it.
   - Add per-predicate unit + parity coverage tests.
4. Re-run the sweep; `nativeCoverage` should rise.
