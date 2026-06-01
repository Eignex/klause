# klause-bench

The benchmarking harness for klause. It separates four orthogonal axes so you control
exactly what runs, and drives everything from a single `bench` CLI.

| Axis | What it is | Where |
|------|------------|-------|
| **format** | how an instance is encoded | `format/` — DIMACS, OPB, JSON-Schema, FlatZinc, MiniZinc, SMT-LIB QF_LIA, XCSP3, in-code |
| **source** | where the bytes come from | `source/` — vendored in `corpus/`, built in-code, or an external collection fetched on demand |
| **solver** | which engine solves | `solver/` — klause LS + backtrack + LogicNG + brute-force, plus in-process reference adapters |
| **metric** | what is measured | `metric/` — time, uniformness, completeness, verify, parity, anytime, coverage, audit, tuning |

A **target** (`target/Targets.kt`) binds a set of catalog suites to a metric. The catalog
(`catalog/Suites.kt`) is the single source of truth for which problems exist — nothing
discovers instances from random directories.

No external solver binaries are used: the `minizinc` CLI only *compiles* `.mzn`→`.fzn`;
everything is solved in-process. Reference solvers for differential metrics are the
**`klause-choco`** (Choco, complete) and **`klause-ortools`** (OR-Tools CP-SAT, anytime)
adapter modules.

## Quick start

```bash
./gradlew :klause-bench:bench --args="list"            # targets, suites, metrics, usage
./gradlew :klause-bench:bench --args="parity-core"     # klause vs Choco on the in-process core
./gradlew :klause-bench:bench --args="mzn-coverage-smoke"   # % native-predicate coverage
./gradlew :klause-bench:listCorpus                     # suites + external collections (license, cache status)
```

Tune any knob with `-Dklause.*` system properties (all forwarded to the run); e.g.
`-Dklause.bench.repetitions=3`, `-Dklause.bench.mzn.timeoutSec=30`.

## Commands

```
bench <target-id>                   run a predefined target
bench run <metric> [filters…]       ad-hoc: run any metric over any selection (no target needed)
bench preview <metric> [filters…]   print the instances a run would cover, without running
bench list [<suite>]                list targets+suites, or the problems in one suite
bench diag:backtrack                BacktrackSolver SolveStats over a generated PHP/3-SAT series
bench diag:cbls <name|fzn>          CBLS feasibility-plateau diagnostic
```

**Filters** (for `run`/`preview`): `suite=a,b` `category=SAT,OPTIMIZATION` `tag=…`
`name=<glob>` `per-family=N` `max=N` `seed=N` `reference=choco|ortools` `timeout=<ms>`.

```bash
# ad-hoc: parity of just the UNSAT hand-built instances, against OR-Tools
./gradlew :klause-bench:bench --args="run parity suite=handwritten-core category=UNSAT reference=ortools"

# preview a capped, seeded sample of the MiniZinc Challenge benchmarks (fetched on demand)
./gradlew :klause-bench:bench --args="preview coverage suite=mzn-bench per-family=1 max=20 seed=7"
```

## Metrics

| Metric | Reports | Output |
|--------|---------|--------|
| `time` | wall-time for solve/sample/enumerate per backend + propagation microbench; regression vs `bench-baseline.json` | `build/bench-time.json` |
| `uniformness` | sampling distinctness / Hamming spread / entropy; coverage + KL when the space is enumerable | `build/bench-uniformness.json` |
| `completeness` | distinct SAT assignments reached under wall-time budgets | `build/bench-completeness.json` |
| `verify` | cross-backend SAT/UNSAT agreement + every sample satisfies the model (gate) | console |
| `parity` | klause vs a reference (Choco/OR-Tools) on the same `Problem`, checked vs recorded `Expected` | `build/parity-report.json` |
| `anytime` | klause-LS vs a reference: time-to-first/best, best objective, solutions seen | `build/anytime-report.{json,md}` |
| `coverage` | % constraint predicates klause handles natively vs MiniZinc-decomposed (push toward 100%) | `build/coverage-report.{json,md}` |
| `audit` | compile-only: native\|decomposed tally per family + `klause-fzn-cli` ingest smoke | `build/compile-audit-report.{json,md}` |
| `tuning` | rank klause solver configs over a mixed sat+opt workload (avg dense rank, per-goal) | `build/tuning-report.{json,md}` |

Parity/anytime reference defaults: parity→Choco, anytime→OR-Tools. Override per run with
`reference=…` (ad-hoc) or `-Dklause.bench.parity.reference=` / `-Dklause.bench.anytime.reference=`.

## The catalog

Suites and problems are declared in `catalog/Suites.kt` with a small DSL. To **add a problem**,
edit a suite:

```kotlin
val dimacsCore = suite("dimacs-core", "Curated small DIMACS CNF") {
    format = Format.DIMACS; license = "SATLIB-style (public benchmarks)"
    vendored("php4", Category.UNSAT, Expected.Unsat)          // file under corpus/dimacs/php4.cnf
    inCode("threeClauses", Category.SAT, Expected.Sat) { Problem(...) }   // built in Kotlin
    workspace("queens", "klause-mzn-lib/test-models/queens.mzn", Category.CSP, Expected.Sat)
    external("uf20-1", ExternalCollections.satlibUf20, "uf20-01.cnf", Category.SAT, Expected.Sat)
}
```

A `ProblemSource` is one of: **`Vendored`** (tracked in `corpus/`), **`InCode`** (built in
Kotlin), or **`External`** (inside a fetched collection). To **add a comparison**, add a
`Target` in `target/Targets.kt` — the two stay independent.

## Corpus & fetching

Vendored problems live in `corpus/` (see `corpus/PROVENANCE.md`). Non-redistributable
collections (MiniZinc Challenge benchmarks, libminizinc tests, hakank, SATLIB) are **fetched
on first use** into `build/corpus-cache/` and declared with their license + reason in
`catalog/Suites.kt → ExternalCollections`. The large MiniZinc corpora are exposed as
*discovered* suites (`mzn-bench`, `libminizinc-tests`, `hakank`) whose instances are selected
by the family-aware machinery in `source/CorpusSelection.kt` (per-family interleave + caps +
deterministic seeded sampling; `pickPrimaryMzn`; `.dzn` pairing). Control selection with
`-Dklause.bench.select.{perFamily,max,seed}` or the `per-family=`/`max=`/`seed=` filters.

```bash
./gradlew :klause-bench:warmCorpus --args="warm all"        # pre-fetch external collections
./gradlew :klause-bench:bench --args="mzn-coverage"         # fetches minizinc-benchmarks on demand
```

## Reference solvers

`klause-choco` and `klause-ortools` map a klause `Problem` into Choco / OR-Tools CP-SAT and
solve it in-process (mirroring the `klause-logicng` / `klause-smt` side-door adapters). They
cover the common factor set and raise an explicit "unsupported factor" error rather than
silently dropping a constraint — so a reference can never quietly disagree by omission.

## Verifying a change

```bash
./gradlew :klause-bench:test                               # unit + parser + selection tests
./gradlew :klause-bench:bench --args="verify-core"         # cross-backend agreement gate
./gradlew :klause-bench:bench --args="parity-core"         # klause vs Choco, vs recorded Expected
./gradlew :klause-fzn-cli:installDist \
  && ./gradlew :klause-bench:bench --args="mzn-audit-smoke" # compile audit incl. ingest smoke
```
