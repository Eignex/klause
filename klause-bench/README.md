# klause-bench

The benchmarking harness for klause. It separates four orthogonal axes so you control exactly what runs, and drives everything from a single bench CLI.

The axes are: **format** (how an instance is encoded), **source** (where the bytes come from), **solver** (which engine solves), and **metric** (what is measured). The catalog (`catalog/Suites.kt`) is the single source of truth for which problems exist; nothing discovers instances from random directories.

A run is fully described by a **metric over a selection of problems**, with an optional reference solver and budget — that is the primary command:

```
bench <metric> [filters…]
```

Presets (`target/Targets.kt`) are just saved shorthands for a `bench <metric> [filters]` line that carry a tuned budget or a curated suite mix. Per-suite or per-reference variants are spelled with filters, not added as presets.

No external solver binaries are used for klause itself. The `minizinc` CLI only compiles models to FlatZinc; everything is solved in-process. The reference solvers for differential metrics are the `klause-choco` (Choco, complete search), `klause-ortools` (OR-Tools CP-SAT, anytime), and `klause-yuck` (Yuck, local search) adapter modules.

## Quick start

```
./gradlew :klause-bench:bench --args="list"                       presets, suites, metrics, usage
./gradlew :klause-bench:bench --args="parity suite=core"          klause vs Choco on the in-process core
./gradlew :klause-bench:bench --args="time-core"                  wall-time + propagation microbench
./gradlew :klause-bench:bench --args="coverage suite=mzn-smoke"   percent native-predicate coverage
```

Tune any knob with `-Dklause.*` properties (all forwarded to the run JVM), e.g. `-Dklause.bench.mzn.timeoutSec=30`.

## Commands

```
bench <metric> [filters…]            run a metric over a selection (primary form)
bench <preset-id>                    run a saved preset (see `list`)
bench preview <metric> [filters…]    print the instances a run would cover, without running
bench list [<suite>]                 list presets+suites, or the problems in one suite
bench diag:backtrack                 BacktrackSolver SolveStats over a generated PHP/3-SAT series
bench diag:cbls <name|fzn>           CBLS feasibility-plateau diagnostic
bench coverage:xcsp3|smtlib          parse/solve rates over a whole format library
```

`run` is accepted as a back-compat alias for the primary form, so `bench run parity …` and `bench parity …` are identical.

### Filters

Filters select the problems a run covers and tune how it runs. They apply to `bench <metric>` and `bench preview`:

| filter | meaning |
|---|---|
| `suite=a,b` | restrict to named suites; the token `core` expands to the in-process core suites |
| `category=SAT,UNSAT,CSP,OPTIMIZATION,…` | keep only these categories |
| `tag=…` | keep only problems carrying a tag |
| `name=<glob>` | substring match, or `*` glob, on the instance name |
| `per-family=N` | for discovered corpora, keep N instances per family |
| `max=N` | cap the total number of instances |
| `seed=N` | seed for deterministic sampling when capping |
| `reference=choco\|ortools\|yuck` | reference solver for differential metrics (parity/anytime) |
| `timeout=<ms>` | per-instance budget for metrics that honor it |
| `profile=cpu\|wall\|alloc` | record the run with JFR (see Profiling) |
| `profile-scope=solve\|all` | profile only the measurement (default) or the whole run |
| `profile-top=N` | rows in the flat profile table (default 40) |

`preview` resolves the selection and prints it without running — use it to check a filter before a long sweep:

```
./gradlew :klause-bench:bench --args="preview parity suite=core category=UNSAT"
```

## Metrics

- **time** — wall-time for `solve` / `samples` / `enumerate` per backend, plus a **propagation microbenchmark** (bake, one-shot pin, incremental per-pin). Writes `build/bench-time.json` and flags regressions past a threshold against `bench-baseline.json`.
- **uniformness** — sampling distinctness, Hamming spread, and entropy; adds coverage and KL divergence when the space is small enough to enumerate.
- **completeness** — distinct SAT assignments reached under a wall-time budget.
- **verify** — cross-backend SAT/UNSAT agreement plus a sample-validity gate (every sampled assignment must satisfy the problem). This is the correctness gate.
- **parity** — solves klause against a reference (Choco / OR-Tools / Yuck) on the same `Problem` and checks both against the recorded `Expected` oracle.
- **anytime** — pits klause-LS against a reference, recording time-to-first, time-to-best, best objective, and solutions seen.
- **search** — runs the complete backtracker under a fixed deterministic CDCL config and reports the engine's own search-size counters (nodes, conflicts, learned clauses) plus solve-rate. Holding the suite fixed and comparing conflicts A/B's a clause-learning or explanation change; the `slack-alldiff` Golomb-ruler suite is the Hall-prone workload for that.
- **coverage** — percent of constraint **predicates** klause handles natively vs MiniZinc-decomposed.
- **audit** — compile-only sweep classifying native vs decomposed per family, plus a `klause-cli` ingest smoke.
- **tuning** — ranks klause solver configs over a mixed sat+opt workload by averaged dense rank.
- **credit** — per-worker portfolio attribution (first/best/sole + marginal ranking).

Parity defaults to the Choco reference, anytime to OR-Tools; override per run with `reference=` or the `-Dklause.bench.parity.reference` / `-Dklause.bench.anytime.reference` properties. Metrics write JSON (and Markdown where useful) under `build/`.

## Presets

Presets are the handful of `bench <metric>` lines worth a name because they carry a tuned budget or a curated suite mix. Everything else is a filter spell. Run `bench list` for the live set; the current ones are:

| preset | metric | what it pins |
|---|---|---|
| `verify-core` | verify | cross-backend agreement over the in-process core |
| `time-core` | time | wall-time + propagation microbench over the in-process core |
| `anytime` | anytime | klause-LS vs OR-Tools over the MiniZinc smoke set, 5 s budget |
| `tune-mixed` | tuning | config ranking over a curated sat+opt mix, 2 s budget |
| `search-slack-alldiff` | search | search-effort A/B over the Golomb suite, 30 s budget |
| `mzn-credit-ls` | credit | LS portfolio credit campaign over the MiniZinc Challenge corpus, 10 s budget |

## Recipes

### Parity against other solvers

```
# klause vs Choco (the default reference) over the in-process core, checked against the oracle
./gradlew :klause-bench:bench --args="parity suite=core"

# klause vs OR-Tools CP-SAT, only the UNSAT instances
./gradlew :klause-bench:bench --args="parity suite=core category=UNSAT reference=ortools"

# klause vs Choco over the curated SMT-LIB / XCSP3 sets
./gradlew :klause-bench:bench --args="parity suite=smtlib-core"
./gradlew :klause-bench:bench --args="parity suite=xcsp3-core"

# parity over the MiniZinc smoke models (needs the `minizinc` CLI to compile to FlatZinc)
./gradlew :klause-bench:bench --args="parity suite=mzn-smoke reference=ortools"

# klause-LS vs Yuck (LS-vs-LS). Provision Yuck first — it is not on Maven Central.
./gradlew :klause-yuck:installYuck
./gradlew :klause-bench:bench --args="parity suite=mzn-smoke reference=yuck"

# parity over a sampled slice of the fetched MiniZinc Challenge corpus
./gradlew :klause-bench:bench --args="parity suite=mzn-bench per-family=1 max=50 seed=1"
```

### Anytime optimization vs a reference

```
./gradlew :klause-bench:bench --args="anytime"                                  # vs OR-Tools, 5 s
./gradlew :klause-bench:bench --args="anytime reference=choco timeout=10000"    # vs Choco, 10 s
./gradlew :klause-bench:bench --args="anytime reference=yuck"                   # vs Yuck (LS-vs-LS)
```

### Micro-optimization / timing benches

```
# wall-time per backend + the propagation microbench, with regression detection
./gradlew :klause-bench:bench --args="time-core"

# more reps and warmup for a tighter measurement
./gradlew :klause-bench:bench --args="time-core" \
  -Dklause.bench.repetitions=11 -Dklause.bench.warmupReps=5 -Dklause.bench.sampleCount=20

# after a known-good run, freeze the current timings as the regression baseline
./gradlew :klause-bench:saveBaseline       # copies build/bench-time.json → bench-baseline.json

# time on one tiny instance for a focused microbench (time benches feasible-expected entries)
./gradlew :klause-bench:bench --args="time suite=core name=cardinality"
```

The regression threshold scales with absolute time (looser for sub-millisecond cells) and is tunable with `-Dklause.bench.regressionThresholdPct`.

### Search-effort A/B (clause learning / explanations)

```
# baseline VSIDS+phase+Luby vs a SAT-optimized preset vs LogicNG; reports nodes/conflicts/fails
./gradlew :klause-bench:bench --args="search-slack-alldiff"

# same metric on your own selection
./gradlew :klause-bench:bench --args="search suite=core category=UNSAT timeout=30000"
```

Because `search` reports the engine's own counters under a fixed deterministic config, a learning or explanation change is A/B'd by holding the suite fixed and comparing the conflict counts before/after.

### Profiling

Add `profile=cpu|wall|alloc` to any run to record it with Java Flight Recorder. It prints a flat top-method table and leaves the raw recording at `build/bench-prof.jfr` for JMC / `jfr print`.

```
# profile only the solve region (parse + MiniZinc compile + corpus fetch are discounted)
./gradlew :klause-bench:bench --args="search suite=slack-alldiff timeout=30000 profile=cpu"

# profile allocations, top 20 frames
./gradlew :klause-bench:bench --args="time suite=core profile=alloc profile-top=20"

# profile the whole run including parse/setup
./gradlew :klause-bench:bench --args="parity suite=xcsp3-core profile=cpu profile-scope=all"
```

`profile-scope=solve` is the default and is what you want for engine work — it starts the recording only after the selection is resolved, so parsing and setup don't pollute the sample set. Sampling is statistical (1 ms period), so pair `profile=` with a long-running selection or a fixed budget for a meaningful table.

For a deeper, whole-JVM native profile, the gradle async-profiler hook is still available:

```
./gradlew :klause-bench:bench --args="search-slack-alldiff" \
  -PasyncProfiler=/path/to/libasyncProfiler.so -PprofFormat=traces=30 -PprofOut=build/prof.txt
```

### Config tuning and portfolio credit

```
./gradlew :klause-bench:bench --args="tune-mixed"        # rank klause solver configs by avg dense rank
./gradlew :klause-bench:bench --args="mzn-credit-ls"     # per-worker portfolio attribution campaign
```

### Coverage and audits

```
./gradlew :klause-bench:bench --args="coverage suite=mzn-smoke"   # native-predicate % on the smoke models
./gradlew :klause-bench:bench --args="coverage suite=mzn-bench"   # …over the fetched Challenge corpus
./gradlew :klause-cli:installJvmDist
./gradlew :klause-bench:bench --args="audit suite=mzn-smoke"      # native|decomposed per family + ingest smoke
```

### Whole-library format coverage

Distinct from the bare `coverage` metric: the colon-suffixed `coverage:*` fetches an entire external format library and reports how many instances **parse** into a klause `Problem`, how many **solve** within a budget, and which **unsupported constructs** account for the rest (the actionable bucket for closing parser/factor gaps).

```
./gradlew :klause-bench:bench --args="coverage:xcsp3"
./gradlew :klause-bench:bench --args="coverage:smtlib" \
  -Dklause.coverage.solve=false      # parse-only
```

Knobs: `-Dklause.coverage.{solve,timeMs,maxBytes,limit,progressEvery}`.

### Diagnostics

```
./gradlew :klause-bench:bench --args="diag:backtrack"        # SolveStats over a generated PHP/3-SAT series
./gradlew :klause-bench:bench --args="diag:cbls <name|fzn>"  # CBLS feasibility-plateau forensics
```

## The catalog

Suites and problems are declared in `catalog/Suites.kt` with a small DSL. To add a problem, edit a suite with one of: `vendored` (a file under `corpus/`), `inCode` (built in Kotlin), `workspace` (a file elsewhere in the repo), or `external` (inside a fetched collection). To run a new comparison, spell it as `bench <metric> [filters]`; only add a `Target` in `target/Targets.kt` when the invocation carries non-obvious config worth a name. The two stay independent.

## Corpus and fetching

Vendored problems live in `corpus/` (see `corpus/PROVENANCE.md`). Non-redistributable collections (MiniZinc Challenge benchmarks, libminizinc tests, hakank, SATLIB) are fetched on first use into `build/corpus-cache/` and declared with their license and reason in `ExternalCollections`. The large MiniZinc corpora are exposed as discovered suites (`mzn-bench`, `libminizinc-tests`, `hakank`) whose instances are selected by the family-aware machinery in `source/CorpusSelection.kt` (per-family interleave, caps, deterministic seeded sampling, `pickPrimaryMzn`, dzn pairing). Control selection with the `per-family`, `max`, and `seed` filters or the matching `-Dklause.bench.select.*` properties. Collections are fetched automatically the first time a run needs them.

For parallel sweeps, `-Dklause.bench.shard=i/n` keeps every n-th selected instance starting at i (0-based). Sharding is applied before resolution, so disjoint shards never race on the shared mzn→fzn cache:

```
./gradlew :klause-bench:bench --args="parity suite=mzn-bench" -Dklause.bench.shard=0/4
```

## Reference solvers

`klause-choco`, `klause-ortools`, and `klause-yuck` map a klause `Problem` into Choco, OR-Tools CP-SAT, and Yuck and solve it in-process (Yuck via a provisioned subprocess), mirroring the `klause-logicng` and `klause-smt` side-door adapters. They cover the common factor set and raise an explicit unsupported-factor error rather than silently dropping a constraint, so a reference can never quietly disagree by omission.

## Verifying a change

```
./gradlew :klause-bench:test                                   unit, parser, and selection tests
./gradlew :klause-bench:bench --args="verify-core"             cross-backend agreement gate
./gradlew :klause-bench:bench --args="parity suite=core"       klause vs Choco, vs recorded Expected
./gradlew :klause-cli:installJvmDist && ./gradlew :klause-bench:bench --args="audit suite=mzn-smoke"
```
