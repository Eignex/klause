# klause-bench

The benchmarking harness for klause. It separates four orthogonal axes so you control exactly what runs, and drives everything from a single bench CLI.

The axes are: format (how an instance is encoded), source (where the bytes come from), solver (which engine solves), and metric (what is measured). A target binds a set of catalog suites to a metric. The catalog (catalog/Suites.kt) is the single source of truth for which problems exist; nothing discovers instances from random directories.

No external solver binaries are used. The minizinc CLI only compiles models to FlatZinc; everything is solved in-process. Reference solvers for differential metrics are the klause-choco (Choco, complete search) and klause-ortools (OR-Tools CP-SAT, anytime) adapter modules.

## Quick start

  ./gradlew :klause-bench:bench --args="list"            targets, suites, metrics, usage
  ./gradlew :klause-bench:bench --args="parity-core"     klause vs Choco on the in-process core
  ./gradlew :klause-bench:bench --args="mzn-coverage-smoke"   percent native-predicate coverage

Tune any knob with -Dklause.* properties (all forwarded to the run), e.g. -Dklause.bench.mzn.timeoutSec=30.

## Commands

  bench <target-id>                   run a predefined target
  bench run <metric> [filters]        ad-hoc: run any metric over any selection, no target needed
  bench preview <metric> [filters]    print the instances a run would cover, without running
  bench list [<suite>]                list targets+suites, or the problems in one suite
  bench diag:backtrack                BacktrackSolver SolveStats over a generated PHP/3-SAT series
  bench diag:cbls <name|fzn>          CBLS feasibility-plateau diagnostic

Filters for run/preview: suite=a,b, category=SAT,OPTIMIZATION, tag=..., name=<glob>, per-family=N, max=N, seed=N, reference=choco|ortools, timeout=<ms>.

Example: bench run parity suite=handwritten-core category=UNSAT reference=ortools

## Metrics

time measures wall-time for solve/sample/enumerate per backend plus a propagation microbench, with regression detection against bench-baseline.json. uniformness measures sampling distinctness, Hamming spread, and entropy, adding coverage and KL when the space is enumerable. completeness counts distinct SAT assignments reached under wall-time budgets. verify is the cross-backend SAT/UNSAT agreement and sample-validity gate. search runs the complete backtracker under a fixed deterministic CDCL config and reports the engine's own search-size counters (nodes, conflicts, learned clauses) plus solve-rate, so a clause-learning or explanation change can be A/B'd by holding the suite fixed and comparing conflicts; the slack-alldiff suite of Golomb rulers is the Hall-prone workload for that.

parity solves klause against a reference (Choco or OR-Tools) on the same Problem and checks both against the recorded Expected oracle. anytime pits klause-LS against a reference, recording time-to-first, time-to-best, best objective, and solutions seen. coverage reports the percent of constraint predicates klause handles natively vs MiniZinc-decomposed. audit is a compile-only sweep classifying native vs decomposed per family plus a klause-cli ingest smoke. tuning ranks klause solver configs over a mixed sat+opt workload by averaged dense rank.

Parity defaults to the Choco reference, anytime to OR-Tools; override per run with reference= or the -Dklause.bench.parity.reference / -Dklause.bench.anytime.reference properties. Metrics write JSON (and Markdown where useful) under build/.

## The catalog

Suites and problems are declared in catalog/Suites.kt with a small DSL. To add a problem, edit a suite with one of: vendored (a file under corpus/), inCode (built in Kotlin), workspace (a file elsewhere in the repo), or external (inside a fetched collection). To add a comparison, add a Target in target/Targets.kt. The two stay independent.

## Corpus and fetching

Vendored problems live in corpus/ (see corpus/PROVENANCE.md). Non-redistributable collections (MiniZinc Challenge benchmarks, libminizinc tests, hakank, SATLIB) are fetched on first use into build/corpus-cache/ and declared with their license and reason in ExternalCollections. The large MiniZinc corpora are exposed as discovered suites (mzn-bench, libminizinc-tests, hakank) whose instances are selected by the family-aware machinery in source/CorpusSelection.kt (per-family interleave, caps, deterministic seeded sampling, pickPrimaryMzn, dzn pairing). Control selection with the per-family, max, and seed filters or the matching -Dklause.bench.select.* properties. Collections are fetched automatically the first time a run needs them.

## Reference solvers

klause-choco and klause-ortools map a klause Problem into Choco and OR-Tools CP-SAT and solve it in-process, mirroring the klause-logicng and klause-smt side-door adapters. They cover the common factor set and raise an explicit unsupported-factor error rather than silently dropping a constraint, so a reference can never quietly disagree by omission.

## Verifying a change

  ./gradlew :klause-bench:test                               unit, parser, and selection tests
  ./gradlew :klause-bench:bench --args="verify-core"         cross-backend agreement gate
  ./gradlew :klause-bench:bench --args="parity-core"         klause vs Choco, vs recorded Expected
  ./gradlew :klause-cli:installDist && ./gradlew :klause-bench:bench --args="mzn-audit-smoke"
