# klause-bench

The benchmarking harness for klause. It separates four orthogonal axes — **format** (how an instance is encoded), **source** (where the bytes come from), **solver** (which engine solves), **metric** (what is measured) — and drives everything from one CLI. The catalog (`catalog/Suites.kt`) is the single source of truth for which problems exist; nothing discovers instances from random directories.

A run is fully described by a **metric over a selection of problems**, plus an optional reference solver and budget. That is the only command form:

```
bench <metric> [filters…]      e.g.  bench parity suite=smtlib-core reference=ortools
```

Presets (`target/Targets.kt`) are named shorthands for a `bench <metric>` line that carries non-obvious config (a tuned budget or a curated suite mix) — nothing else.

No external solver binaries are used for klause itself; the `minizinc` CLI only compiles models to FlatZinc, and everything is solved in-process. References for differential metrics are the `klause-choco` (complete), `klause-ortools` (CP-SAT, anytime), and `klause-yuck` (local search) adapters.

## Quick start

```
./gradlew :klause-bench:bench --args="list"                       suites, presets, metrics, usage
./gradlew :klause-bench:bench --args="parity suite=core"          klause vs Choco on the in-process core
./gradlew :klause-bench:bench --args="verify suite=core"          cross-backend agreement gate
./gradlew :klause-bench:bench --args="coverage suite=mzn-smoke"   percent native-predicate coverage
```

Tune any knob with `-Dklause.*` properties (forwarded to the run JVM), e.g. `-Dklause.bench.mzn.timeoutSec=30`.

## Commands

```
bench <metric> [filters…]            run a metric over a selection
bench <preset-id>                    run a saved preset (see `list`)
bench preview <metric> [filters…]    print what a run would cover, without running
bench list [<suite>]                 list suites+presets, or the problems in one suite
bench diag:backtrack | diag:cbls <x> diagnostics
bench format-coverage:xcsp3|smtlib   parse/solve rates over a whole format library
```

## Filters

| filter | meaning |
|---|---|
| `suite=a,b` | restrict to named suites; `suite=core` expands to the in-process core |
| `kind=cop\|csp` | keep optimization (COP) or satisfaction (CSP) problems — classified from the source's objective directive (MiniZinc `solve minimize/maximize`, OPB `min:`, SMT-LIB `(minimize`, XCSP3 `<objective>`); applied before sampling, so a capped `kind` selection fills its cap |
| `category=SAT,UNSAT,CSP,OPTIMIZATION,…` | keep only these categories |
| `tag=…` / `name=<glob>` | tag membership / substring-or-`*`-glob on the instance name |
| `per-family=N` `max=N` `seed=N` | cap and deterministically sample (discovered corpora) |
| `reference=choco\|ortools\|yuck` | reference solver for parity/anytime |
| `timeout=<ms>` | per-instance budget for metrics that honor it |
| `profile=cpu\|wall\|alloc` `profile-scope=solve\|all` `profile-top=N` | JFR profiling (below) |

## Metrics

- **time** — wall-time for solve/sample/enumerate per backend, plus a propagation microbench. Writes `build/bench-time.json` and flags regressions vs `bench-baseline.json` (`:klause-bench:saveBaseline` freezes the current run as the baseline).
- **verify** — cross-backend SAT/UNSAT agreement + sample-validity gate. The correctness gate.
- **parity** — klause vs a reference on the same problem, both checked against the recorded `Expected` oracle.
- **anytime** — klause-LS vs a reference: time-to-first, time-to-best, best objective, solutions seen.
- **search** — complete backtracker under a fixed CDCL config; reports nodes/conflicts/learned + solve-rate. A/B a learning or explanation change by holding the suite fixed and comparing conflicts (the `slack-alldiff` Golomb suite is the Hall-prone workload).
- **uniformness** / **completeness** — sampling distinctness/spread/entropy; distinct SAT assignments reached under budget.
- **coverage** / **audit** — percent of constraint predicates handled natively vs MiniZinc-decomposed; compile-only native/decomposed classification + a `klause-cli` ingest smoke.
- **tuning** / **credit** — rank solver configs by avg dense rank; per-worker portfolio attribution.

Parity defaults to Choco, anytime to OR-Tools; override with `reference=`. Metrics write JSON (and Markdown where useful) under `build/`.

## Recipes

```
# parity against each reference (Yuck needs `:klause-yuck:installYuck` first)
bench parity suite=core category=UNSAT reference=ortools
bench parity suite=mzn-smoke reference=yuck
bench parity suite=mzn-bench per-family=1 max=50 seed=1     # sampled slice of the fetched corpus

# anytime optimization vs a reference, with a budget
bench anytime reference=choco timeout=10000

# timing microbench with more reps
bench time suite=core -Dklause.bench.repetitions=11 -Dklause.bench.warmupReps=5

# search-effort A/B on your own selection
bench search suite=core category=UNSAT timeout=30000

# compile audit (needs `:klause-cli:installJvmDist`)
bench audit suite=mzn-smoke
```

(drop the `./gradlew :klause-bench:bench --args="…"` wrapper for brevity above.)

### Profiling

`profile=cpu|wall|alloc` records the run with Java Flight Recorder, prints a flat top-method table, and leaves `build/bench-prof.jfr` for JMC / `jfr print`. `profile-scope=solve` (default) starts the recording only after the selection is resolved, so parse/compile/fetch are discounted; `profile-scope=all` covers the whole run. Sampling is statistical (1 ms), so pair it with a long-running selection.

```
bench search suite=slack-alldiff timeout=30000 profile=cpu
bench parity suite=xcsp3-core profile=cpu profile-scope=all
```

For a deeper whole-JVM native profile, the gradle hook is still available: `-PasyncProfiler=/path/to/libasyncProfiler.so [-PprofFormat=traces=30] [-PprofOut=…]`.

### Whole-library format coverage

Distinct from the `coverage` metric: `format-coverage:xcsp3|smtlib` fetches an entire external format library and reports how many instances parse, how many solve within a budget, and which unsupported constructs block the rest (the gap list for parser/factor work). Knobs: `-Dklause.coverage.{solve,timeMs,maxBytes,limit,progressEvery}`.

## Catalog, corpus, and selection

Suites and problems are declared in `catalog/Suites.kt`. Add a problem with `vendored` (a small file under `smoke-corpus/`), `inCode` (built in Kotlin), `workspace` (a file elsewhere in the repo), or `external` (inside a fetched collection). Add a `Target` only when an invocation carries config worth a name.

Vendored problems live in `smoke-corpus/` — small, fast instances meant to exercise parsers and cross-check solvers, not to stress them (see `smoke-corpus/PROVENANCE.md`). Non-redistributable collections (MiniZinc Challenge, libminizinc, hakank, SATLIB) are fetched on first use into `build/corpus-cache/` and declared with license + reason in `ExternalCollections`; the large MiniZinc corpora are exposed as discovered suites (`mzn-bench`, `libminizinc-tests`, `hakank`) selected by the family-aware machinery in `source/CorpusSelection.kt`. For parallel sweeps, `-Dklause.bench.shard=i/n` keeps every n-th selected instance (applied before resolution, so shards never race on the mzn→fzn cache).

## Reference solvers

`klause-choco`, `klause-ortools`, and `klause-yuck` map a klause `Problem` into Choco, OR-Tools CP-SAT, and Yuck and solve it in-process (Yuck via a provisioned subprocess). They cover the common factor set and raise an explicit unsupported-factor error rather than silently dropping a constraint, so a reference can never quietly disagree by omission.

## Verifying a change

```
./gradlew :klause-bench:test                              unit, parser, and selection tests
./gradlew :klause-bench:bench --args="verify suite=core"  cross-backend agreement gate
./gradlew :klause-bench:bench --args="parity suite=core"  klause vs Choco, vs recorded Expected
```
