# Benchmarking Klause

The harness selects instances from a catalog and runs one Klause configuration per
invocation. Run a second configuration separately and compare the saved results offline.
All Gradle commands below run from the repository root; the installed bench launcher
uses `klause-bench` as its working directory.

## Build and run

```sh
./gradlew :klause-bench:bench --args="list"
./gradlew :klause-bench:bench --args="preview suite=core"
./gradlew :klause-bench:solveCampaign --args="solve suite=core"
```

`solveCampaign` builds `:klause-cli:installJvmDist` and selects Gradle's JDK for the
subprocess. For a generic `bench solve` run, rebuild the CLI first:

```sh
./gradlew :klause-cli:installJvmDist
./gradlew :klause-bench:bench --args="solve suite=core timeout=30000"
```

`installDist` is a different task and leaves a stale CLI binary. The JVM distribution
requires JDK 25 or newer; see [CLI setup](cli.md#build-and-run). `-Dklause.*` properties
are forwarded to the bench JVM. Keep the installed CLI and runtime fixed throughout
a campaign.

## Commands

| Command | Purpose |
|---|---|
| `list [suite]` | List suites or their problems |
| `preview [filters]` | Inspect selection without solving |
| `select [filters]` | Emit selected instance identities as JSON lines; `features=true` adds structural features |
| `solve [filters]` | Run one configuration over the selection |
| `solve-one suite=<id> problem=<name> [filters] [out=<dir>]` | Run exactly the instance named by `select` and save a case record |
| `validate-solution suite=<id> problem=<name> output=<path>` | Source-check an existing MiniZinc output without solving again |
| `provenance out=<path>` | Capture installed-distribution and runtime identities |
| `corpus status`, `corpus gc`, `corpus compress [path]` | Inspect, evict or compress fetched collections |

## Named sets

`klause-bench/sets/<name>.txt` lists a fixed set of problems, one `<suite>/<problem>` per line as `select` prints
them; `@<set>` includes another set and `#` starts a comment. `set=<name>` selects it, so every bench that names a set
runs the same problems as the corpus evolves. Each focused set covers one theme, what
solving the problem asks of klause, as `select features=true` classifies it (`InstanceClassifier.THEMES`): open-domain
integers, linear reals, scheduling, routing or packing globals, other globals, MIP, SAT, MaxSAT, PB. `sweep` includes every focused set. A set changes only on purpose,
and a material change takes a new name, so results under one name stay comparable.

## Filters

| filter | meaning |
|---|---|
| `suite=a,b` | restrict to named suites; `suite=core` expands to the in-process core |
| `set=a,b` | the problems of named sets in `klause-bench/sets/` (see Named sets), whole: no suite default cap applies; with `suite=`, only the set problems in those suites |
| `kind=cop\|csp` | keep optimization (COP) or satisfaction (CSP) problems — classified from the source's objective directive (MiniZinc `solve minimize/maximize`, OPB `min:`, SMT-LIB `(minimize`, XCSP3 `<objective>`); checked as each family's cap is filled, so a capped `kind` selection fills its cap without reading every source |
| `category=SAT,UNSAT,CSP,OPTIMIZATION,…` | keep only these categories |
| `tag=…` / `name=<glob>[,…]` | tag membership / comma-separated OR of substring-or-`*`-glob patterns on the instance name (e.g. `name=cvrp,nfc,mario`) |
| `per-family=N` `max=N` `seed=N` | cap and deterministically sample. Families are the corpus's own (the XCSP3 series, the MiniZinc challenge problem, …) and are counted per suite. A discovered suite's default cap (1 per family for most) applies only when `per-family` is unset; `per-family` replaces it, and `seed` samples within each family |
| `balance=format` | split `max` evenly across the formats present, water-filling short formats' surplus into larger ones — so a broad multi-format sweep touches every format instead of filling with the format that has the most families |
| `timeout=<ms>` | per-instance solve budget |
| `label=<name>` | free-form run tag folded into the `<config>` dir name (e.g. a klause version / fix name), so re-running the same config coexists as a distinct dir instead of overwriting — then `compare.sh` the two. |
| `solver-seed=N` | the solver's random seed (`-r`); unset keeps the bench's fixed seed. A non-default seed is folded into the `<config>` dir name, so seeds of one config coexist |
| `engine=fixed\|cp\|mixed\|ls` `processors=N` | klause's search for a `solve` run (below), forwarded to the cli `-e`/`-p` (the cli owns the engine model). `engine` unset ⇒ no `-e`, so klause follows the cli's own default engine (the bench has no engine default of its own). |
| `lp=off\|conservative\|default\|aggressive[±id…]` | klause only: forwarded as klause-cli `--lp` (the LP-relaxation emphasis / per-technique deltas). Folded into the `<config>` dir name, so `lp=off` vs `lp=aggressive` coexist — run twice and `compare.sh` to A/B the LP cost/benefit |
| `exact=true\|false` | klause only: forwards `--exact` when true, preserving continuous FlatZinc floats and requiring exact MPS certificates. Exact and default runs use separate result directories and cache keys. |
| `param=key=value` (repeatable) | klause-cli `--param` engine knobs forwarded verbatim. `var-selector`/`val-selector` edit the pool on `engine=cp`, and select the heuristic on `engine=fixed` for an unannotated model — run `solve` twice with different selectors, then `compare.sh` the two dirs. Folded into the `<config>` dir name so runs don't clobber |
| `profile=cpu\|wall\|alloc` `profile-scope=solve\|all` `profile-top=N` | JFR profiling (below) |


## Installed-build provenance

Klause cache keys include SHA-256 hashes of every installed distribution file (launcher and
all dependency jars), the selected Java runtime files, OS/architecture and inherited Java/runtime
options. Hashes use relative paths and file contents, so a byte-identical rebuild reuses results;
a replaced jar invalidates results even if the launcher timestamp and source commit are unchanged.
Keep the installed distribution and runtime fixed while a campaign runs. For a campaign that starts a
bench JVM per case, capture their hashes once after installing the CLI and bench:

```bash
cd klause-bench
./build/install/klause-bench/bin/klause-bench provenance out=build/provenance.json
export KLAUSE_BENCH_PROVENANCE="$PWD/build/provenance.json"
```

Each bench process validates the manifest's absolute roots, file set, identities, sizes, modification
and change times before reusing the hashes. Manifests require Unix file metadata; changed files or a
copied installation are refused. Generate a fresh manifest after installing or restoring a build on
each host. Without the environment variable, the bench hashes the installed trees itself.
Inherited runtime options are captured for each case. The harness-only `-Dklause.bench.cache`,
`-Dklause.bench.corpusCache` and `-Dklause.workspace.root` properties are excluded from the
fingerprint; solver JVM options and input-processing properties remain.

Per-problem JSON records carry `buildProvenance`, `buildFingerprint` and `validationPolicy`.
`gitSha` describes the harness checkout and does not identify the installed solver. Cache keys also
include the original solver settings, before output-label sanitization, and the validation policy.
Legacy records decode with absent provenance; they cannot supply the installed-build identity.

Launcher hashes include the default Vector API/native-access flags; runtime options capture inherited
overrides. These identify the requested kernel environment. They do not identify the vendor BLAS
library actually selected by a solve, external native libraries, or MiniZinc's compilation toolchain.
Those measurements and broader acceptance records remain separate work. A passing gate or a cache
hit does not establish performance or LP certification.

Bounded opt-in checks use existing fixtures:

```
./gradlew :klause-bench:solveCampaign --args="solve suite=dimacs-core name=implication-chain timeout=1000 label=acceptance"
./gradlew :klause:basisTrace --args="verify"
./gradlew :klause:basisTrace --args="replay"
```

The solve writes its verdict and provenance beside the raw stream. The basis tasks emit their own
verification/replay records; missing fixtures and numerical declines retain their existing semantics.
None of these campaigns run as part of `check`.

## Saved results

See [paired lab record analysis](benchmark-records.md) for complete-block
comparisons and analyzing external evidence through CI.

Each problem writes a raw `<problem>.out` stream and a self-describing
`<problem>.json` record under `klause-bench/output/<config>/`. Configuration names
include settings, budgets and optional labels. Records contain verdict, objective
orientation, proof status, statistics and portfolio attribution. `solve-one` also
writes a record on load failure: `stats.unsupported` means declined input and
`stats.loadError` means compilation/parsing failed.

The harness output directories are ignored staging locations. Keep campaign bundles
needed for active work in the shared external `klause-evidence/campaigns/<name>/`
directory and clear obsolete outputs. The [evidence policy](development.md#campaign-evidence)
defines what belongs in the repository and when to retire campaign material.

`elapsedMs` measures subprocess wall time, including launch and output consumption.
It differs from `timeToBestMs` and `timeToFirstFeasibleMs`, which stay null without
a witness. Legacy records and runs that never launched can have null elapsed time.
Analysis prefers witness time, then valid reported `stats.solveTime` in seconds,
then elapsed time, then the budget. Undecided runs retain the budget penalty.

SMT-LIB optimization records consume the CLI's objective comments and retain its
terminal `optimizationStatus` in `stats`. An optimum requires `sat`, a finite objective
and explicit `optimal` status. Plain `sat`, interrupted incumbents (`best-found`) and
unbounded objectives receive no optimality proof credit; an unbounded record's objective
belongs to its feasible witness.

Records retain plain model/data `sourceHashes` and the final rendered candidate
as `finalWitness`, up to 8 MiB. These captures are outside subprocess timing.
Missing witnesses and hashes remain absent rather than being inferred from a
verdict. Included MiniZinc files and compiler identity are outside the original
model/data hashes.

Under `-s`, portfolios emit one `%%%klause-arm:` attribution line per installed
incumbent. The exact discrete `objective=<Long>` channel is saved as decimal-text
`exactObjective`, preserving values past `2^53`. A `continuousObjective=<Double>`
channel appears when continuous columns contribute cost and represents the whole
model-oriented objective. A missing continuous channel is not a zero offset.
Legacy numeric objectives remain readable.

LP telemetry uses `lpPhase_<phase>_<route>_Calls`, `_Nanos` and outcome keys for
completed attempts, including declines. Acceptance timing includes exact-dual
recovery and cleanup, so those totals overlap. The exact ladder is a separate
inclusive phase. Work and pivot counters do not replace elapsed timing; absent
metrics do not mean zero cost.

## Source validation

MiniZinc float runs pin the final DZN candidate into the original source and
recompile with MiniZinc's standard library and the same data seed. `sourceValidation`
is `valid`, `invalid` or `unknown`; `floatApproximation` records the lowering policy.
Residual constraints/variables, compiler failure or the ten-second checking timeout
leave validation unknown. Checking is outside the solve budget.

Rejected candidates and unchecked grid witnesses receive no solution credit.
A checked grid witness establishes source feasibility, but a grid optimum or
refutation cannot prove the source result. `reportedFeasible`, `reportedObjective`
and `reportedProven` retain the original claims. Earlier incumbents are not
independently checked; attribution is limited to the final objective.

Integer campaigns opt in with `param=source-validation=true`, consumed by the bench
before launching the CLI. They also retain the full stdout hash in
`sourceOutputSha256`. Invalid candidates lose solution/proof credit. Candidates
with unknown checking remain reported witnesses with their status. Witness checking
does not independently establish optimality or infeasibility.

```sh
./gradlew :klause-bench:bench --args="validate-solution suite=hakank problem=arbitrage_loops/arbitrage_loops output=/path/to/solver.out"
```

## Comparing configurations

Run each configuration separately over the same frozen selection:

```sh
./gradlew :klause-bench:solveCampaign --args="solve suite=core kind=cop engine=cp param=var-selector=vsids timeout=30000 label=vsids"
./gradlew :klause-bench:solveCampaign --args="solve suite=core kind=cop engine=cp param=var-selector=smallest-domain timeout=30000 label=domain"
```

Use `klause-bench/output/compare.sh [--incomplete] <dirA> <dirB>` on the resulting
configuration directories. It compares shared problems by feasibility, proof and
direction-aware objective quality, with time breaking ties. Complete mode prefers
proved optimality; incomplete mode ignores it and splits ties equally. The report
includes scores, wins/losses/ties and contradictory outcome warnings. Saved files
also support comparisons across rebuilt Klause revisions using distinct labels.

Freeze source hashes, membership, required proof strength, limits, seeds and
resolved binaries before tuning. Compare matching source semantics. Include
preparation, replay, failed recovery, certification and disposal in consumer cost.
Warmup plus alternating paired repetitions and timing spread help distinguish
host variation from a reproducible change. A pivot/allocation reduction or faster
kernel is not a complete-solve improvement by itself.

## Catalog and corpus

[Suites.kt](../klause-bench/src/main/kotlin/com/eignex/klause/bench/catalog/Suites.kt)
declares suites. Problems can be vendored files, Kotlin-generated instances,
workspace files or members of fetched collections. Vendored inputs are small
parser/solve fixtures; [provenance](testing/fixtures.md) records their origins.
Larger collections are fetched into `~/.cache/klause-bench/corpus/`, respecting
`XDG_CACHE_HOME` or `-Dklause.bench.corpusCache`. Collection declarations retain
license and redistribution policy.

Family-aware sampling applies before resolution. `-Dklause.bench.shard=i/n`
selects every nth chosen instance before compilation-cache access. MiniZinc
Challenge discovery excludes families without `.dzn`/`.json` data and
`2010/depot_placement`, whose legacy annotation does not compile. Exclusions
precede sampling and caps.

Discovered suites carry `Category` and `Format`. Classification adds structure,
global/linear counts, Boolean-heavy status and logic: declared SMT-LIB logic or
MPS LP/MIP markers. Theme sets use these structural features. The catalog exposes
SMT-LIB logics handled by Klause's difference, LIA and LRA/LIRA theories.

## Compression and caches

Fetched CNF, WCNF, OPB, WBO, XML, SMT-LIB and MPS inputs can be stored as `.zst`.
MiniZinc source/data and cloned collections remain plain. `zstd` must be on
`PATH`; Klause decompresses inside measured solve time. Compression does not
change instance names or content keys: hashes use decompressed bytes. Migration
writes each archive beside the original, verifies the decompressed SHA-256, then
replaces it; interrupted migration can be resumed.

```sh
./gradlew :klause-bench:bench --args="corpus compress"
./gradlew :klause-bench:bench --args="corpus compress /path/to/collection"
./gradlew :klause-bench:bench --args="corpus status"
./gradlew :klause-bench:bench --args="corpus gc"
```

The corpus cache defaults to a decimal 60 GB cap. Configure it with
`-Dklause.bench.corpus.maxGb=N` or `KLAUSE_BENCH_CORPUS_MAX_GB=N`; `0`/`off`
disables eviction. Whole least-recently-used collections are evicted before/after
fetch. This JVM's used collections stay pinned; parallel shards should fetch
first or disable the cap. Collection metadata avoids walking all files. Eviction
renames to `.trash` before deletion and resumes cleanup on the next pass.

Result caching is separate under `klause-bench/build/bench-cache/` and uncapped.
Keys include plain input contents, settings, budget, validation policy and installed
build/runtime provenance. An unchanged byte-identical rebuild can reuse results.
Use `-Dklause.bench.cache=false` for fresh measurements.

## Profiling and diagnostics

`profile=cpu|wall|alloc` records JFR, prints a flat method table and writes
`klause-bench/build/bench-prof.jfr`. `profile-scope=solve` starts after selection
resolution; `all` covers the full harness. Sampling is statistical.

Normal solves are subprocesses. Profiling switches to an in-process single-engine
path for `cp`/`fixed` or `ls`; it cannot profile the mixed pool. This path writes
no ordinary result JSON/cache and is a solver diagnostic rather than a CLI timing
measurement.

An AWS lab whole-CLI recording uses `KLAUSE_LAB_PROFILE_DIR` to identify the
completed `cli.jfr`. The subprocess harness summarizes it on the runner after the
captured CLI elapsed time ends. Tables attribute Java CPU samples and sampled
allocation weights to projection preparation, state allocation, seeding, invariant
setup, move selection/application, backtrack and presolve. ALNS bootstrap and
repair stacks carry separate prefixes. Allocation weights estimate bytes; CPU
sample shares are not wall-time attribution. Retain the raw recording and process
resource report outside the repository, and compare performance with uninstrumented
paired runs.

```sh
./gradlew :klause-bench:bench --args="solve suite=mzn-bench name=mario engine=cp profile=cpu timeout=30000"
./gradlew :klause-bench:intDomainMicrobench
```

The Gradle async-profiler hook accepts `-PasyncProfiler=/path/to/libasyncProfiler.so`,
`-PprofEvent=cpu|alloc|wall`, `-PprofFormat=traces=30` and `-PprofOut=<path>`.
Keep profiler observations separate from uninstrumented paired timings.
[LP capture/replay](lp/replay.md) covers basis and owner-lifecycle diagnostics.

To mine saved portfolio cases, use
`bench mine [by=config|suite|family|format|category|kind] <cases.json> ...`.
It reports best-holder share, set cover, work and reward signals for arm catalog
analysis. Retain every case and disclose quarantined arms.

## Validation

```sh
./gradlew :klause-bench:test
./gradlew :klause-bench:solveCampaign --args="solve suite=core"
```

Benchmarks and timing probes are opt-in. Repository checks use the
[development validation policy](development.md#validation).
