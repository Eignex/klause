# klause-bench

The benchmarking harness for klause. It separates four orthogonal axes — **format** (how an instance is encoded), **source** (where the bytes come from), **solver** (which engine solves), **metric** (what is measured) — and drives everything from one CLI. The catalog (`catalog/Suites.kt`) is the single source of truth for which problems exist; nothing discovers instances from random directories.

A run is fully described by a **metric over a selection of problems**, plus an optional reference solver and budget. That is the only command form:

```
bench <metric> [filters…]      e.g.  bench solve suite=smtlib-core backend=ortools
```

Presets (`target/Targets.kt`) are named shorthands for a `bench <metric>` line that carries non-obvious config (a tuned budget or a curated suite mix) — nothing else.

No external solver binaries are used for klause itself; the `minizinc` CLI only compiles models to FlatZinc, and everything is solved in-process. The `solve` metric runs **one** solver per invocation — klause, or one of the in-process reference adapters `klause-choco` (complete), `klause-ortools` (CP-SAT), `klause-yuck` (local search). There is no in-session comparison: to compare solvers, run `solve` once per backend (each writes its own `build/solve-<solver>.json`) and diff the saved files offline — so one solver's crash or warmup never contaminates another's baseline.

## Quick start

```
./gradlew :klause-bench:bench --args="list"                       suites, presets, metrics, usage
./gradlew :klause-bench:bench --args="solve suite=core"           klause solves the in-process core
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
bench coverage:xcsp3|smtlib          parse/solve rates over a whole format library
```

## Filters

| filter | meaning |
|---|---|
| `suite=a,b` | restrict to named suites; `suite=core` expands to the in-process core |
| `kind=cop\|csp` | keep optimization (COP) or satisfaction (CSP) problems — classified from the source's objective directive (MiniZinc `solve minimize/maximize`, OPB `min:`, SMT-LIB `(minimize`, XCSP3 `<objective>`); applied before sampling, so a capped `kind` selection fills its cap |
| `category=SAT,UNSAT,CSP,OPTIMIZATION,…` | keep only these categories |
| `tag=…` / `name=<glob>[,…]` | tag membership / comma-separated OR of substring-or-`*`-glob patterns on the instance name (e.g. `name=cvrp,nfc,mario`) |
| `per-family=N` `max=N` `seed=N` | cap and deterministically sample (discovered corpora) |
| `backend=<minizinc solver id>` | the single solver `solve` runs as a subprocess: a registered MiniZinc solver (`choco`/`gecode`/`yuck`/…) via `minizinc --solver`; unset (or `klause`) runs klause via `klause-cli`. Alias `reference=`. |
| `timeout=<ms>` | per-instance budget for metrics that honor it |
| `engine=backtrack\|ls\|mixed` `processors=N` `fixed=true` | klause's search for a `solve` run (below); `engine`/`processors` mirror the CLI's `--engine` / `-p`, `fixed` follows the model annotation |
| `profile=cpu\|wall\|alloc` `profile-scope=solve\|all` `profile-top=N` | JFR profiling (below) |

## Metrics

- **time** — wall-time for solve/sample/enumerate per backend, plus a propagation microbench. Writes `build/bench-time.json` and flags regressions vs `bench-baseline.json` (`:klause-bench:saveBaseline` freezes the current run as the baseline).
- **verify** — cross-backend SAT/UNSAT agreement + sample-validity gate. The correctness gate.
- **solve** — run one backend (`backend=`, default klause) over the selection **as a subprocess** (klause via `klause-cli`, references via `minizinc --solver <id>`), emitting MiniZinc-format output. Records per-instance objective + time-to-best (optimization) or feasibility (satisfaction) + proof status + `%%%mzn-stat` statistics, to `build/solve-<solver>.json`; the raw per-instance output is saved under `build/solve-<solver>/`. Run once per backend and diff offline. klause solving needs `:klause-cli:installJvmDist`; because klause-cli renders the *model's* objective, maximize values are reported in the model's orientation (sign-correct against references).
- **search** — complete backtracker under a fixed CDCL config; reports nodes/conflicts/learned + solve-rate. A/B a learning or explanation change by holding the suite fixed and comparing conflicts (the `slack-alldiff` Golomb suite is the Hall-prone workload).
- **uniformness** / **completeness** — sampling distinctness/spread/entropy; distinct SAT assignments reached under budget.
- **coverage** / **audit** — percent of constraint predicates handled natively vs MiniZinc-decomposed; compile-only native/decomposed classification + a `klause-cli` ingest smoke.
- **tuning** / **credit** — rank solver configs by avg dense rank; per-worker portfolio attribution.

Metrics write JSON (and Markdown where useful) under `build/`.

## Recipes

```
# solve a corpus with each backend, one invocation each (Yuck needs `:klause-yuck:installYuck`)
bench solve suite=mzn-bench per-family=1 max=50 seed=1                 # klause (default), sampled slice
bench solve suite=mzn-bench per-family=1 max=50 seed=1 backend=choco   # Choco baseline, same selection
bench solve suite=mzn-bench per-family=1 max=50 seed=1 backend=yuck    # Yuck baseline
# each writes build/solve-<solver>.json — diff them offline to compare

# timing microbench with more reps
bench time suite=core -Dklause.bench.repetitions=11 -Dklause.bench.warmupReps=5

# search-effort A/B on your own selection
bench search suite=core category=UNSAT timeout=30000

# compile audit (needs `:klause-cli:installJvmDist`)
bench audit suite=mzn-smoke
```

### Solve: klause competition tracks as filter combinations

When `solve` runs klause (no `backend=`), the klause side is `engine` (`backtrack`/`ls`/`mixed`) over
`processors` workers — the portfolio's two axes, mirroring the CLI's `--engine` / `-p`. `processors=1`
is the single-core sequential portfolio. The MiniZinc / XCSP competition tracks are just combinations
of these, so there are no baked-in track names — spell each as a recipe (all compose with
`kind=cop|csp` and `timeout=`):

```
# open (default): multi-thread, mixed engines
bench solve suite=mzn-bench timeout=300000

# free: 1 thread, klause's own search (the single-core sequential portfolio)
bench solve suite=mzn-bench processors=1 timeout=300000

# parallel: multi-thread, a single engine (backtrack-only)
bench solve suite=mzn-bench engine=backtrack processors=8 timeout=300000

# local search only
bench solve suite=mzn-bench engine=ls timeout=300000
```

The one exception is the **fixed** track — follow the model's `int_search` annotation — which has no
filter combination, so it's a flag of its own; models without an annotation fall back to free search.
When the baseline backend is Choco, `fixed=true` mirrors the annotation onto Choco too (sound now that
the LCG fixed-search bug is worked around).

```
bench solve suite=mzn-bench fixed=true timeout=300000               # klause
bench solve suite=mzn-bench fixed=true backend=choco timeout=300000 # Choco, same annotation
```

(drop the `./gradlew :klause-bench:bench --args="…"` wrapper for brevity above.)

### Profiling

`profile=cpu|wall|alloc` records the run with Java Flight Recorder, prints a flat top-method table, and leaves `build/bench-prof.jfr` for JMC / `jfr print`. `profile-scope=solve` (default) starts the recording only after the selection is resolved, so parse/compile/fetch are discounted; `profile-scope=all` covers the whole run. Sampling is statistical (1 ms), so pair it with a long-running selection.

```
bench search suite=slack-alldiff timeout=30000 profile=cpu
bench solve suite=xcsp3-core profile=cpu profile-scope=all
```

For a deeper whole-JVM native profile, the gradle hook is still available: `-PasyncProfiler=/path/to/libasyncProfiler.so [-PprofFormat=traces=30] [-PprofOut=…]`.

### Whole-library format coverage

Distinct from the bare `coverage` metric: the colon-suffixed `coverage:xcsp3|smtlib` fetches an entire external format library and reports how many instances parse, how many solve within a budget, and which unsupported constructs block the rest (the gap list for parser/factor work). Knobs: `-Dklause.coverage.{solve,timeMs,maxBytes,limit,progressEvery}`.

## Catalog, corpus, and selection

Suites and problems are declared in `catalog/Suites.kt`. Add a problem with `vendored` (a small file under `smoke-corpus/`), `inCode` (built in Kotlin), `workspace` (a file elsewhere in the repo), or `external` (inside a fetched collection). Add a `Target` only when an invocation carries config worth a name.

Vendored problems live in `smoke-corpus/` — small, fast instances meant to exercise parsers and cross-check solvers, not to stress them (see `smoke-corpus/PROVENANCE.md`). Non-redistributable collections (MiniZinc Challenge, libminizinc, hakank, SATLIB) are fetched on first use into `build/corpus-cache/` and declared with license + reason in `ExternalCollections`; the large MiniZinc corpora are exposed as discovered suites (`mzn-bench`, `libminizinc-tests`, `hakank`) selected by the family-aware machinery in `source/CorpusSelection.kt`. For parallel sweeps, `-Dklause.bench.shard=i/n` keeps every n-th selected instance (applied before resolution, so shards never race on the mzn→fzn cache).

## Reference solvers

The reference path depends on the instance's format:

- **MiniZinc instances** run the reference **end-to-end via `minizinc --solver <id>`** on the original `.mzn`(+`.dzn`) — the competition setup, where the solver compiles the model with **its own** globals library and uses its native propagators. This is the faithful baseline. Yuck is registered out of the box; Choco is provisioned with `./gradlew :klause-bench:installChoco` (fetches the choco-parsers FlatZinc jar + Choco's `mzn_lib` globals and registers `choco.msc` under `~/.minizinc/solvers`, so `minizinc --solver choco` runs Choco with its own native globals).
- **Non-MiniZinc formats** (XCSP3 / OPB / DIMACS / SMT — no `.mzn` to hand to `minizinc`) fall back to the in-process adapters `klause-choco` / `klause-ortools` / `klause-yuck`, which map a klause `Problem` into the reference and solve it in-process. They raise an explicit unsupported-factor error rather than silently dropping a constraint.

The in-process adapters re-derive the reference from klause's **already-decomposed** `Problem`, so on MiniZinc models they would inherit klause's lowering (e.g. a `subcircuit` that klause turned into clauses, or an internal `GaussianXor`) instead of the solver's native global — which is exactly why the MiniZinc path bypasses them.

## Running the parity sweep

The parity sweep measures klause against the reference solvers on the MiniZinc Challenge corpus. The method is fixed so every run is comparable:

1. **One backend per `bench solve` invocation** — there is no in-session comparison (a crash or warmup can't contaminate another solver's number). Each run writes `build/solve-<solver>.json`; comparison is done **offline**, direction-aware (maximize vs minimize), by diffing the saved files: `parity-runs/compare.sh <A.json> <B.json>`. Results are also content-addressed in `build/bench-cache/` (keyed by `sha256(model+data) · time-settings · solver+settings`), so re-running an identical instance replays instantly — reference baselines stay frozen while klause iterates (klause's key folds in the cli-binary mtime). Disable with `-Dklause.bench.cache=false`.
2. **Curated selection, not random sampling** — a fixed set chosen to span distinct global constraints, so a small set still exercises the breadth that matters. The MiniZinc Challenge itself is ~95% optimization, so the set is COP-heavy by design.
   - **8 COP**: `elitserien` (alldifferent, global_cardinality, inverse, member, regular), `gfd-schedule` (cumulative, at_most, nvalue), `cargo` (cumulative, diffn), `is` (among, circuit, table), `nfc` (network_flow), `mario` (path, subcircuit), `evilshop` (cumulative, disjunctive), `zephyrus` (arg_sort, lex_less).
   - **2 CSP**: `multi-knapsack` (knapsack), `oocsp_racks` (global_cardinality, increasing, element).
   - Spell it with the `name=` OR filter: `name=elitserien,gfd-schedule,cargo,is/*,nfc,mario,evilshop,zephyrus` (the `is/*` glob anchors the family so it doesn't substring-match e.g. `opt-cryptanalysis`).
3. **Tracks** (each a `solve` filter combination; see the recipes above). klause: `parallel` (`engine=backtrack processors=8`), `open` (`processors=8`), `free` (`processors=1`), `fixed` (`fixed=true`), `ls` (`engine=ls`). References: Choco for the complete tracks, Yuck for `ls`.
4. **Budgets**: complete tracks **300000 ms**, the LS track **180000 ms**.

```
# LS track: klause local search vs Yuck, 180s, on the curated set
bench solve suite=mzn-bench kind=cop per-family=1 name=elitserien,gfd-schedule,cargo,is/*,nfc,mario,evilshop,zephyrus engine=ls       timeout=180000
bench solve suite=mzn-bench kind=cop per-family=1 name=elitserien,gfd-schedule,cargo,is/*,nfc,mario,evilshop,zephyrus backend=yuck    timeout=180000
# then: parity-runs/compare.sh klause-bench/build/solve-klause-ls-x8.json klause-bench/build/solve-yuck.json
```

The `parity-runs/` scripts (`run-sweep.sh` full, `run-ls.sh` LS-only, `compare.sh` for offline diffs) drive this with `-PbenchHeap=32g` and save per-run logs+JSON; they are local scratch (not committed). To stop a background sweep, kill the `run-*.sh` bash loop **first** (else it spawns the next leg), then the forked `BenchCli` JVM by PID.

## Verifying a change

```
./gradlew :klause-bench:test                              unit, parser, and selection tests
./gradlew :klause-bench:bench --args="verify suite=core"  cross-backend agreement gate
./gradlew :klause-bench:bench --args="solve suite=core"   klause solves the in-process core
```
