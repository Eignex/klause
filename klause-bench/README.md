# klause-bench

JVM catalog, corpus management, subprocess runner and offline measurement tools.
This README owns benchmark usage, records, validation and diagnostics. Solver
contracts live in [docs](../docs/README.md); committed input identities and
licenses live in [fixture provenance](../docs/testing/fixtures.md).
Commands run from the repository root unless shown otherwise.

| Task | Read |
|---|---|
| Run or select cases | [Build](#build-and-run), [selection](#selection-and-named-sets), [corpus](#catalog-and-corpus) |
| Run remote experiments or regression schedules | [Klause-lab](#klause-lab) |
| Identify and compare measurements | [Provenance](#installed-build-provenance), [records](#saved-results), [comparisons](#comparing-configurations) |
| Check source claims | [Validation](#source-validation), [offline verifier](#offline-source-verifier) |
| Investigate cost | [Profiling](#profiling), [focused probes](#focused-probes), [LP replay](../docs/lp/replay.md) |

## Build and run

```sh
./gradlew :klause-bench:bench --args="list"
./gradlew :klause-bench:bench --args="preview suite=core"
./gradlew :klause-bench:solveCampaign --args="solve suite=core"
```

`solveCampaign` builds `:klause-cli:installJvmDist` and selects Gradle's JDK for the
subprocess. Before a generic `bench solve`, rebuild the CLI explicitly:

```sh
./gradlew :klause-cli:installJvmDist
./gradlew :klause-bench:bench --args="solve suite=core timeout=30000"
```

`installDist` leaves a stale CLI binary. The installed launcher requires JDK 25
or newer; see [CLI runtime setup](../docs/cli.md#build-and-run). The installed bench
launcher uses `klause-bench` as its working directory. `-Dklause.*` properties are
forwarded to the bench JVM. Keep the CLI distribution and runtime fixed during a run.

| Command | Purpose |
|---|---|
| `list [suite]` | List suites or problems |
| `preview [filters]` | Inspect selection without solving |
| `select [filters]` | Emit instance identities as JSON lines; `features=true` adds structural features |
| `solve [filters]` | Run one configuration over the selection |
| `solve-one suite=<id> problem=<name> [filters] [out=<dir>]` | Run one selected identity and save a case record |
| `validate-solution suite=<id> problem=<name> output=<path>` | Check saved MiniZinc or XCSP3 output without solving again |
| `provenance out=<path>` | Capture installed-build and runtime identities |
| `corpus status`, `corpus gc`, `corpus compress [path]` | Inspect, evict or compress fetched collections |
| `mine [by=config\|suite\|family\|format\|category\|kind] <cases.json> ...` | Report portfolio holder share, set cover, work and rewards; retain quarantined cases |

## Selection and named sets

`sets/<name>.txt` fixes membership as one `<suite>/<problem>` identity per line,
matching `select`; `@<set>` includes another set and `#` starts a comment.
Focused sets cover the structural themes reported by `select features=true`:
open integers, linear reals, scheduling, routing, packing, other globals, MIP,
SAT, MaxSAT and PB. `sweep` includes them all. Material membership changes take
new names so results under one name stay comparable.

`continuous-mip-v1` fixes 167 MIPLIB 2017 optimization sources with both integer
and continuous columns. It includes finite and open routes and selects every member
without a changing reference filter. It is separate from the smaller `mip` theme set.

| Filter | Meaning |
|---|---|
| `suite=a,b` | Restrict suites; `core` expands to the in-process core |
| `set=a,b` | Select whole named sets without default suite caps; `suite=` intersects membership |
| `kind=cop\|csp` | Classify optimization/satisfaction from source objective directives while filling family caps |
| `category=SAT,UNSAT,CSP,OPTIMIZATION,…` | Restrict categories |
| `tag=…`, `name=<glob>[,…]` | Tag membership or OR of substring/`*`-glob name patterns |
| `per-family=N`, `max=N`, `seed=N` | Cap and deterministically sample within each suite's own families; unset `per-family` uses the suite default |
| `balance=format` | Split `max` across present formats, redistributing unused shares |
| `timeout=<ms>`, `label=<name>` | Per-instance budget and optional configuration tag |
| `solver-seed=N` | CLI seed (`-r`); unset uses the harness's fixed seed |
| `engine=fixed\|cp\|mixed\|ls`, `processors=N` | Forward CLI `-e`/`-p`; unset engine uses the CLI default |
| `lp=off\|conservative\|default\|aggressive[±id…]` | Forward the CLI LP ceiling and technique deltas |
| `exact=true\|false` | Forward `--exact`; exact/default results and cache keys remain separate |
| `param=key=value` | Repeatable CLI engine parameters; unknown/malformed keys fail at the CLI |
| `profile=cpu\|wall\|alloc`, `profile-scope=solve\|all`, `profile-top=N` | Opt-in JFR diagnostics |

Settings, budgets, non-default solver seeds, parameters and labels distinguish
configuration directories. Run configurations separately to compare them.
`param=source-validation=true` opts MiniZinc integer and XCSP3 runs into independent
source-witness checking and is consumed by the bench before CLI launch.

## Klause-lab

[Klause-lab](https://github.com/Eignex/klause-lab) owns remote experiment execution,
regression schedules and reference-result storage. It builds the Klause revisions
named by an experiment, selects problems through `klause-bench select`, and runs
each problem/configuration/seed/repetition with `solve-one`. The lab's queue,
live output, comparisons and regression trends are served over HTTP.
Deployment, client connection settings, AWS execution, reference filtering and
scheduling are documented in its maintained
[setup and operations guide](https://github.com/Eignex/klause-lab/blob/main/deploy/SETUP.md).

Save experiment specifications in the shared external evidence directory. For
example, an LP-ceiling comparison uses the lab's configuration maps:

```json
{
  "name": "lp-ceiling-ab",
  "description": "Compare LP off and default on the same MPS inputs and solver seeds.",
  "problems": { "suite": "mps-core", "reference": "any" },
  "base": { "ref": "main", "engine": "cp", "timeout": "10000" },
  "configs": [
    { "label": "off", "lp": "off" },
    { "label": "default", "lp": "default" }
  ],
  "seeds": [1, 7],
  "repeats": 3,
  "host": "lab",
  "parallel": 1
}
```

`problems` takes a selection map or a list of maps, each naming `suite` or `set`.
`base` is overlaid by each `configs` entry and crossed with optional `grid` axes.
Configuration values are strings; `param.<name>` becomes CLI `--param <name>=<value>`.
Use immutable commit IDs for reproducible revision comparisons. Selection reference
filters can change membership; `reference=any` keeps the benchmark selection, and
named sets default to that policy. Record the actual planned inputs and hashes.

From the separate `klause-lab` checkout, using its configured client:

```sh
./deploy/lab run /absolute/path/to/klause-evidence/campaigns/lp-ceiling/experiment.json
./deploy/lab show 123
./deploy/lab cases 123
./deploy/lab csv 123
./deploy/lab files 123
./deploy/lab cat 123 job.log 8000
./deploy/lab fetch 123 /absolute/path/to/klause-evidence/campaigns/lp-ceiling/raw
```

Replace `123` with the returned job ID. `lab cases` prints a tabular summary;
the HTTP `GET /experiments/<id>/cases` endpoint supplies the JSON array consumed by
`tools/analyze_lab.py` and `bench mine`. `lab fetch` downloads records and raw
outputs, including `cases/<index>/record.json` for the offline source verifier.
`lab compare` links experiments; `lab schedule`, `reschedule`, `schedules` and
`check` manage regression runs under the lab's operations guide.

`lab files` lists a job's files and `lab cat <id> <path> [tail-bytes]` prints one, such as
`job.log`, `setup.log` or `cases/<index>/record.json`, over HTTP: reading a job needs no SSH.
`lab ssh <id> [instance] [command]` reaches a running AWS job's instance. Measure as a lab
experiment rather than directly on the lab Mac, where a run competes with the lab's cases.
A measurement no experiment expresses, such as timing a component on captured inputs, runs as
a script job: `lab script <file> <ref> <description>` runs the file once in a build of `<ref>`,
on AWS by default, and keeps its output and whatever it writes to `$OUT` with the job. Script
jobs are not for tests, lint or other gates; CI runs those.

Cases disable result caching, alternate configurations across seeds/repetitions,
and retain installed-build provenance when supported by the selected revision.
XCSP3 references accept only `cp-sat`; `solve-one backend=reference solver=cp-sat`
uses the CPMpy importer and OR-Tools engine. Unsupported solver names are rejected
before invocation and cannot supply a reference verdict under another engine's label.
Check failed, unsupported and missing cases before interpreting comparisons.
Lab agreement and regression confirmation remain recorded-result evidence;
[independent source validation](#source-validation) defines stronger checks.
`profileCli` requires AWS execution and explicit `parallel: 1`; its instrumented
timing stays separate from ordinary comparisons.

## Catalog and corpus

[Suites.kt](src/main/kotlin/com/eignex/klause/bench/catalog/Suites.kt) declares
vendored, generated, workspace and fetched inputs. Collection declarations own
license and redistribution policy. Family sampling precedes resolution;
`-Dklause.bench.shard=i/n` selects every nth chosen input before compilation-cache
access. MiniZinc Challenge instances with data use `year/family/data` keys.
Data-free instances use `year/family/model`, retaining the selected model's relative
path without `.mzn`; families retain `year/family`. Bare data-free family reference
keys cannot identify that model or be reused for its instance key. Discovery excludes
2026 families without `.dzn`/`.json` data and `2010/depot_placement`, whose legacy
annotation does not compile, before caps.
Categories/formats, global/linear counts, Boolean-heavy structure and SMT logic
or MPS LP/MIP markers feed classification and theme sets.

`miplib2017-csp` removes free (`N`) rows and their column/RHS/range references.
Other coefficients, ranged sides, bounds and integrality remain source-identical.
Objective-only columns retain zero coefficients in a constraint row; models
without constraints receive a zero equality. Derived feasibility inputs have
separate content-addressed cache entries, reference namespaces and source hashes.

Fetched collections live in `~/.cache/klause-bench/corpus/`, respecting
`XDG_CACHE_HOME` or `-Dklause.bench.corpusCache`. The decimal 60 GB default cap is
configured by `-Dklause.bench.corpus.maxGb=N` or `KLAUSE_BENCH_CORPUS_MAX_GB=N`;
`0`/`off` disables eviction. Whole least-recently-used collections are evicted
before/after fetch. This JVM's used collections stay pinned; parallel shards
should prefetch or disable eviction. Metadata avoids full tree scans; `.trash`
renames make interrupted deletion resumable.

CNF, WCNF, OPB, WBO, XML, SMT-LIB and MPS collections can use `.zst`; MiniZinc
source/data and cloned collections stay plain. `zstd` must be on `PATH` and
CLI decompression is inside measured solve time. Hashes use decompressed bytes.
Compression writes beside the original, verifies SHA-256, then replaces it;
interrupted migration can resume.

Result caching is separate and uncapped under `build/bench-cache/`. Keys include
plain input contents, original unsanitized settings, budget, validation/objective
representation versions and installed-build/runtime provenance. Byte-identical
rebuilds can reuse results. Use `-Dklause.bench.cache=false` for fresh measurements.

## Installed-build provenance

Cache keys hash every installed launcher/dependency jar, selected Java runtime
files, OS/architecture and inherited runtime options. Relative paths and bytes
identify the installation; launcher timestamps and source commits alone do not.
For repeated per-case bench JVMs, capture identities once after installation:

```sh
cd klause-bench
./build/install/klause-bench/bin/klause-bench provenance out=build/provenance.json
export KLAUSE_BENCH_PROVENANCE="$PWD/build/provenance.json"
```

Each process checks absolute roots, file sets, identities, sizes, modification
and change times before reusing hashes. Manifests require Unix metadata and
refuse changed/copied installations; regenerate after installation or restoration
on each host. Without the variable, the bench hashes installed trees itself.
Inherited options are captured per case; harness-only `klause.bench.cache`,
`klause.bench.corpusCache` and `klause.workspace.root` properties are excluded.
Solver JVM and input-processing options remain in the fingerprint.

Records carry `buildProvenance`, `buildFingerprint` and `validationPolicy`.
`gitSha` identifies the harness checkout. Legacy records without provenance
cannot identify installed solver binaries. Launcher/runtime hashes describe
requested Vector API/native-access options, but do not identify the selected
vendor BLAS, external libraries or MiniZinc compiler. Capture those separately.

## Saved results

Each problem writes `<problem>.out` and `<problem>.json` under ignored
`output/<config>/`. Records retain verdict, objective direction, proof status,
statistics and portfolio attribution. `solve-one` writes load failures too:
`stats.unsupported` means declined input; `stats.loadError` means parsing/compilation
failed. Source model/data hashes and `finalWitness` (up to 8 MiB) are captured
outside subprocess timing. Missing captures remain absent; included MiniZinc
files and compiler identity are outside original model/data hashes.

| Field/channel | Contract |
|---|---|
| `elapsedMs` | Subprocess launch through output consumption; nullable for legacy/unlaunched runs |
| `timeToBestMs`, `timeToFirstFeasibleMs` | Nullable without a witness; distinct from total elapsed time |
| `exactObjective` | Canonical integer/reduced rational text; authoritative for comparisons and references |
| `objective` | Legacy approximate display; may be null outside binary64 range |
| `optimizationStatus` | SMT terminal status in `stats`; optimum credit needs `sat`, a valid exact objective and explicit `optimal` |
| `%%%klause-arm:` | One attribution line per installed incumbent under `-s` |
| `objective=<Long>` | Exact discrete attribution value, preserving integers beyond `2^53` |
| `continuousObjective=<Double>` | Whole model-oriented objective when continuous columns contribute; absence is not a zero offset |
| `lpPhase_<phase>_<route>_Calls`, `_Nanos`, outcomes | Completed attempts, including declines; acceptance includes dual recovery/cleanup and overlaps other phases |
| `armFailure.<label>` | Bounded CLI JSON diagnostic; a retired arm need not invalidate a sibling verdict |

Exact values take precedence over numeric fallbacks; malformed exact values earn
no objective credit. `best-found`, plain `sat` and unbounded witnesses earn no
optimality credit; an unbounded record's objective belongs to its feasible point.
The exact ladder's phase timing is inclusive. Missing metrics remain missing,
and work/pivots do not substitute for elapsed time.

Outputs are staging locations. Store active notes, scripts, manifests, records
and reports in the shared external `klause-evidence/campaigns/<name>/` directory
alongside the primary checkout. All worktrees share it. Promote reusable tools,
fixtures and architectural conclusions, then delete obsolete campaign bundles.
The [evidence policy](../docs/development.md#campaign-evidence) prohibits committed
raw/generated archives and campaign GitHub workflows.

Publish evidence needed by active reviews as downloadable snapshot assets on the
public Klause-lab `active-evidence-<campaign>` prerelease. The lab's
[active evidence workflow](https://github.com/Eignex/klause-lab/blob/main/deploy/SETUP.md#active-campaign-evidence)
owns staging, per-file manifests, anonymous reviewer downloads and verification
before extraction. Preserve experiment/selection, raw records/profiles, executed
source/build identities, validation provenance and separately generated summaries;
disclose missing evidence and checking limits. Link the release and pin the
manifest's SHA-256 in the review. Release assets survive CI expiry and lab job
cleanup while needed. After promoting useful results and checking all active
consumers, delete obsolete release assets/tags, ended lab jobs and external copies
under that workflow; do not retain a historical archive.

## Source validation

MiniZinc float runs independently check the final complete witness; integer runs
opt in with `param=source-validation=true`. The cache retains paired FlatZinc/DZN
`.ozn` mappings keyed by source/data and solver-library contents. Compiler outputs
are reconstructed, source variables pinned, then recompiled with the standard
library and matching data seed. The pinned source objective is compared exactly.

Authoritative rational coordinates travel as `% klause-exact: name = value;`
comments beside decimal assignments and survive in `finalWitness`. Mixed exact/grid
witnesses use `% klause-coordinate:` with `% klause-float-component:` provenance;
the grid coordinate is the exact binary64 value of the decoded point, not an exact
source claim. An authoritative objective coordinate overrides decimal display.
Pure grid witnesses use original MiniZinc source pinning. Mixed witnesses use the
independent flattened-text checker across every component. The witness policy is
`minizinc-source-witness-v3`. `sourceValidation` is `valid`,
`invalid` or `unknown`; `sourceValidationScope` distinguishes `minizinc-source`
from `flatzinc-binary64`. The independent flattened-text checker interprets float
literals as exact binary64 rationals and checks supported predicates/bounds; it
does not establish decimal-rational MiniZinc semantics. `floatApproximation`
records the lowering policy. See [input semantics](../docs/formats.md).

Unsupported syntax, missing coordinates, residual constraints, compiler failure
or the ten-second external timeout leave validation unknown. Checking is outside
the solve budget; hashes must match and stay stable. Rejected/unchecked grid
candidates earn no solution credit. A checked grid witness proves feasibility,
not a source optimum/refutation. `reportedFeasible`, `reportedObjective` and
`reportedProven` retain the original claims. Integer opt-in also captures stdout
SHA-256 as `sourceOutputSha256`; unknown checking retains the reported witness.
Earlier incumbents are unchecked, so attribution is limited to the final objective.

XCSP3 integer runs opt in with `param=source-validation=true`. The installed bench
includes the independent `org.xcsp:xcsp3-tools:2.5` `SolutionChecker`, also used by
the ACE distribution. It checks a copied original XML source against the final
complete named integer instantiation and its exact reported objective. Every
source variable must appear once, including unconstrained variables and expanded
array cells. Duplicate, unknown, missing, malformed and out-of-domain coordinates,
violated constraints and incorrect objectives are rejected. Compact variable/value
forms are outside this checking surface. Reified/soft constraints, multiple
objectives, unsupported checker callbacks, oversized integer coordinates and the
ten-second/512 MiB checker subprocess limit leave checking `unknown`. Before the
pinned checker's signed 32-bit direct-sum callbacks run, a range guard checks each
assigned product and running total, including constant and variable coefficients.
Overflow leaves checking `unknown`, even if later terms cancel it. The source
parser expands array lists, groups, slides and blocks for this guard. Expression
sum lists and logical meta-constraints are outside the guard's supported surface
and leave checking `unknown`; it does not certify arithmetic in other callbacks.

XCSP records use `validationPolicy=xcsp3-source-witness-v2` and
`sourceValidationScope=xcsp3-source`; `sourceValidationChecker` identifies the
pinned checker. `sourceHashes` binds the original plain source before solving
and is checked for stability during validation. `finalWitness` and
`sourceOutputSha256` retain the reported assignment and output identity;
`checkedWitness` retains only a source-valid assignment, with the checked objective
as its `cost` attribute. Invalid candidates earn no feasibility, objective or proof
credit. Unknown checks retain reported claims without a checked witness.
`reported-result-v1` records have no independent checking. Witness checking
establishes feasibility and objective equality; any optimum or infeasibility claim
remains the solver's proof claim, recorded separately as `reportedProven`.

```sh
./gradlew :klause-bench:bench --args="validate-solution suite=hakank problem=arbitrage_loops/arbitrage_loops output=/path/to/solver.out"
./gradlew :klause-bench:bench --args="validate-solution suite=xcsp3-core problem=sum-opt-tiny output=/path/to/solver.out"
```

`validate-solution` prints a structured validation result, source hashes, checker
identity and the checked XCSP assignment when accepted, without solving again.

### Offline source verifier

[verify_source_results.py](tools/verify_source_results.py) checks `mps-core`,
`smtlib-core`, `miplib3` and the SMT corpus suites `smtlib-qfidl`, `smtlib-qflia`,
`smtlib-qflira`, `smtlib-qflra` and `smtlib-qfrdl` against original sources using exact arithmetic.
Vendored inputs are retrieved at the recorded revision; original MIPLIB3 inputs
are retrieved separately. All source hashes must match. `--source-root` instead
reads original uncompressed inputs named `<sourceHashes.model>.txt` from a supplied
directory, allowing the same checks without network retrieval.
The five SMT corpus suites require this directory and their hash-matched original
bytes; they do not fetch a source by corpus path. Other suites are declined.

```sh
python3 -m pip install -r klause-bench/tools/requirements-source-checks.txt
python3 klause-bench/tools/verify_source_results.py /path/to/cases.json \
  --source-root /path/to/original-sources --records-root /path/to/raw-job \
  --output /path/to/source-checks.json
```

Inputs can be JSON, gzip or xz; `--records-root` selects `cases/<index>/record.json`
instead of embedded records. SMT models must cover source sorts and satisfy all
original constraints. MPS points must cover every column with integral integer
coordinates; missing, duplicate, unknown and nonfinite coordinates fail checking.
For a complete SMT witness, the verifier pins every declared source variable and
requires SAT under the original constraints; this also establishes source feasibility.
Witness pins enter an incremental scope only when later optimality queries need
the original unpinned constraints.
SMT records without a witness retain an unpinned source feasibility query. Query
failures identify the problem, expected and returned verdicts, and any UNKNOWN reason.
`--progress /path/to/checked-prefix.jsonl` flushes each completed record, including
cache matches. A failed batch retains this checked prefix without claiming the remaining
records passed; the final JSON output is written only after the whole batch succeeds.
Each solver query has a 120-second allowance; source parsing and witness construction
are outside that query allowance and require an enclosing job budget.
The recorded command's `--exact` flag requires exact source feasibility; its absence
selects default MPS checking even when the arm name ends in `-exact`. Legacy records
without a command use that arm suffix. Default MPS `toleranceAccepted`
is separate from exact feasibility, using [scaled source tolerance](../docs/formats.md#mps).
Original rational coefficients/printed values avoid binary64 row overflow.

MPS objectives retain source coefficients, sense and RHS offsets. Default exact
objective text must match either the printed decimal point or its shortest-decimal
binary64 coordinates; numeric fallback permits only binary64 rounding. Exact-mode
points/objectives require exact equality. Rounded default points can be nonexact
while the original source remains feasible.

Claimed SAT/UNSAT require matching exact source verdicts. A proven finite optimum
needs a feasible source objective and exclusion of every strictly better objective;
tolerance acceptance cannot justify either optimum or infeasibility. Unknown
records make no proof claim. Unbounded claims and unsupported constructs decline.
Unsupported MPS sections/bounds, distinct RHS/range/bound vectors and SMT
function/sort declarations are rejected rather than guessed. The verifier is
independent of paired timing analysis and Klause's internal certificates.

[verify_boolean_source_results.py](tools/verify_boolean_source_results.py) checks
`DIMACS`, `WCNF` and linear `OPB` records against original source bytes in that same
hash-named directory. It accepts complete signed-literal assignments, OPB variable
names and WCNF bitstrings. Clauses, hard weights, Boolean integrality and integer
objectives use exact arithmetic. UTF-8 comments preserve original byte hashes.
Linear OPB bodies can omit the variable-count comment; their range is inferred
from referenced indices. Nonlinear or unsupported syntax is declined.

```sh
python3 klause-bench/tools/verify_boolean_source_results.py /path/to/cases.json \
  --source-root /path/to/original-sources --records-root /path/to/raw-job \
  --output /path/to/boolean-source-checks.json
```

The checker independently excludes better source objectives or refutes source
constraints using the pinned Z3 dependency with a ten-second limit. A recognized
pigeonhole subformula can establish a refutation directly. Timeout leaves proof
checking unknown; a feasible point receives no optimum credit without that check.
Errors are recorded per case and make the command exit unsuccessfully. The checker
supports JSON, gzip and xz inputs and preserves cases with no claimed verdict.

### Polynomial real witness verifier

[verify_nra_source_results.py](tools/verify_nra_source_results.py) independently
checks complete named Real/Bool models against an original polynomial SMT source.
It reconstructs rational coordinates and `root-obj` algebraic coordinates using the
pinned Z3 dependency, substitutes every source variable, and requires every
original assertion and Boolean query assumption to evaluate exactly to true.
Algebraic coordinates use integer defining polynomials and one-based distinct
ordered real-root indices. Approximate decimal coordinates do not establish exact
irrational equalities. Missing, duplicate, unknown or sort-mismatched coordinates
are rejected. Nonpolynomial, mixed-sort, optimization and incremental sources decline.

```sh
python3 klause-bench/tools/verify_nra_source_results.py original.smt2 --witness model.smt2
python3 klause-bench/tools/verify_nra_source_results.py original.smt2 --expected unsat
```

`--witness` uses exact ground algebraic evaluation without a solver search query.
`--expected` separately compares a bounded reference verdict and records
`proofCertificateChecked=false`; agreement does not constitute proof-certificate
validation. Query timeout or a reference disagreement fails the comparison. Output
records the original UTF-8 source SHA-256 and exact checker version. Use the same
pinned requirements as the offline source verifier. Builds and checker regressions
run through the existing build gate; remote experiments retain original sources,
complete models, runtime identities and checking results outside the repository.

## Comparing configurations

Run separate configurations over frozen membership, hashes, settings, proof
strength, budgets, seeds and resolved binaries:

```sh
./gradlew :klause-bench:solveCampaign --args="solve suite=core kind=cop engine=cp param=var-selector=vsids timeout=30000 label=vsids"
./gradlew :klause-bench:solveCampaign --args="solve suite=core kind=cop engine=cp param=var-selector=smallest-domain timeout=30000 label=domain"
```

`output/compare.sh [--incomplete] <dirA> <dirB>` compares shared cases by
feasibility, proof and direction-aware objective quality, using time to break ties.
Complete mode prefers proved optima; incomplete mode ignores proof and splits
ties equally. Reports include contradictory outcomes. Time prefers witness time,
then valid `stats.solveTime` seconds, elapsed time, then budget; undecided runs
keep the budget penalty. Distinct labels compare rebuilt revisions.

[analyze_lab.py](tools/analyze_lab.py) reads JSON/gzip/xz case arrays and compares
arms against `--control` in complete input/seed/repetition blocks. It discloses
missing/error/unsupported pairs, contradictions, source-hash and retained-objective
mismatches; absent hashes remain unverified. Retained `_objective` equality checks
archive consistency, not source feasibility. Exact objective comments/text take
precedence over rounded assignments/numeric fallbacks; approximate equality
cannot tie distinct exact values. Summaries disclose source-validation scope.

Process incumbent clocks include launch, loading, preparation, search and delivery;
legacy attribution clocks start during search. PAR2 charges undecided runs twice
the budget while duration summaries preserve actual overshoot. Own-best timing
must be read with objective quality. Equal-outcome subsets are descriptive, not
causal estimates. Family bootstrap averages seeds/repetitions within base families
with the year removed; one family supplies no interval. Counters keep their
[search](../docs/solver/search.md#statistics) and [presolve](../docs/solver/presolve.md#phase-allowance-and-telemetry) scopes.

Compare matching semantics and include preparation, replay, failed recovery,
certification and disposal in consumer cost. Warm up, alternate paired repetitions
and report spread. Kernel, pivot or allocation improvements alone do not establish
complete-solve gains. Inspect retained arm failures and quarantine separately;
independent witness checks do not by themselves verify optimality/refutation.

### LP lifecycle comparison

[scripts/lp-lifecycle](scripts/lp-lifecycle/) measures the complete CP bounding consumer
across a fixed 21-state push/pop trace, with retained and fresh numerical owners.
The checker independently validates source bounds, primal values, objectives and lower
bounds using a vertex oracle, and requires every owner to close. The trace identity
hashes each observed source bound tuple and its effective exact LP model, so retained
and fresh owners share an identity independent of trail revision history. See
[LP capture/replay](../docs/lp/replay.md) for the trace and measurement contract.

`-PlpLifecycleCli=/absolute/path/to/klause-cli-jvm` selects an installed distribution
for the comparison task. Build pinned distributions with `:klause-cli:installJvmDist`
and retain their build/runtime provenance. Use identical comparator classes, trace,
allowances, JVM options and machine when comparing revisions. Match source outcomes
and numerical work before comparing phase allocations and timing; these measurements
do not establish full tree-search or whole-CLI gains. Keep raw measurements externally.

## Profiling

`profile=cpu|wall|alloc` writes `build/bench-prof.jfr` and prints a flat method table.
`profile-scope=solve` starts after selection resolution; `all` covers the harness.
Profiling uses an in-process single `cp`/`fixed` or `ls` engine, cannot profile
mixed pools and writes no normal result JSON/cache. It is a solver diagnostic,
not CLI timing. Whole-CLI recordings selected by `KLAUSE_LAB_PROFILE_DIR` are
summarized after subprocess timing ends. CPU samples/allocation weights distinguish
projection/state preparation, seeding, invariant setup, moves, backtrack, presolve
and ALNS bootstrap/repair. Resumed initial scoring remains seeding; retained
projection building remains preparation. Allocation weights estimate bytes;
CPU sample shares are not wall-time attribution.

```sh
./gradlew :klause-bench:bench --args="solve suite=mzn-bench name=mario engine=cp profile=cpu timeout=30000"
./gradlew :klause-bench:intDomainMicrobench
```

The Gradle hook accepts `-PasyncProfiler=/path/to/libasyncProfiler.so`,
`-PprofEvent=cpu|alloc|wall`, `-PprofFormat=traces=30`, `-PprofOut=<path>`.
Retain raw profiles/resource reports externally; compare uninstrumented paired
runs separately. Inlining/frame removal does not establish saved CPU time.
[LP capture/replay](../docs/lp/replay.md) covers numerical and owner lifecycles.

### Table profiling

```sh
KLAUSE_CLI_OPTS="-Xmx3g -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -XX:StartFlightRecording=filename=table.jfr,settings=profile" \
  klause-cli/build/install/klause-cli-jvm/bin/klause-cli \
  -e cp --param arms=1 --param bt-arm=conflictDriven --param node-limit=100 \
  -r 3 -s -t 30000 /absolute/path/to/instance.xml
jfr print --json --events jdk.ExecutionSample table.jfr
```

Separate root construction, propagation and explanations. Fixed-node timing
requires matching decisions, failures, restarts, root fixings/depth and both arms
spending the cap. Deadline runs can reach different prefixes. Shared conflict
counters exclude native CP analysis; matching SAT reports are not source checks.

## Focused probes

### Component-split fixtures

[scripts/lp-component-split](scripts/lp-component-split/) generates bounded dense
blocks with matrix `nI + 11^T`, RHS `2n` and exact witness `x = 1`. A redundant
MiniZinc row joins the linked control's blocks. Use `exact=true` with a continuous
objective to exercise CP residual LP certification; grid/exact-satisfaction routes
can differ. The optimization fixture attains 1 at `x = 1`, finite `k = 0` or `1`.

```sh
python3 klause-bench/scripts/lp-component-split/generate.py
python3 klause-bench/scripts/lp-component-split/analyze.py /path/to/cases.json /path/to/raw
python3 klause-bench/scripts/lp-component-split/noise.py /path/to/cases.json
```

Analysis checks source witnesses in `<case-index>.out`, rejects incomplete/failed
records and missing elapsed time, and separates proofs/incumbent time from process
duration. Optional split counters omitted at zero are read as zero. Noise analysis
requires balanced `off/on/on/off` pairs and matching outcomes. Save raw cases externally.

### Coordinator lifecycle probe

[scripts/coordinator-lifecycle](scripts/coordinator-lifecycle/) compares fresh and
instruction-sliced local-search runs over an existing DIMACS fixture such as
[php4](smoke-corpus/dimacs/php4.cnf). Parsing and root
baking are outside measurement; fresh engine/projection construction, initialization,
stepping, publication, work charging and cleanup are inside. Both satisfaction and
Boolean-objective minimization run with a fixed seed and instruction allowance. The
probe checks every published witness against the original clauses and hashes witnesses,
objectives, final counters and retained weights/activity. These checks do not certify
infeasibility. Round zero warms the JVM; rounds one–four repeat the same work.

```sh
./gradlew -I klause-bench/scripts/coordinator-lifecycle/coordinator-lifecycle.init.gradle \
  :klause:coordinatorLifecycleProbe --args="/absolute/path/to/instance.cnf 3 107 32"
```

`-PlifecycleProbeCli=/absolute/path/to/klause-cli-jvm` selects an installed distribution's
classes. Build each pinned distribution with `:klause-cli:installJvmDist` before comparing;
record its installed-build/runtime provenance and the probe source identity. Use the
same fixture, seed, allowance, repetitions, JVM settings and machine. Match hashes,
reported work and witnesses before comparing allocations or time. Thread allocated bytes
are exact for the measured JVM thread; validation, hashing and wrapper dispatch are
included. The probe's prepared-model consumer scope differs from whole-CLI loading,
portfolio scheduling and source completion; pair it with matched uninstrumented CLI
controls before drawing end-to-end conclusions. Save raw measurements externally.

### Repair-chain probe

[scripts/repair-chains](scripts/repair-chains/) measures 1000 proposal calls after
500 ordinary local-search moves. Parsing, baking, setup and restoration checks
are outside timing; round zero warms up and rounds one–four repeat seeded work.
Inputs solved during preparation decline.

```sh
./gradlew -I klause-bench/scripts/repair-chains/repair-chains.init.gradle \
  :klause:repairChainProbe --args="/absolute/path/to/instance.cnf 3"
```

`-PchainProbeCli=/absolute/path/to/klause-cli-jvm` selects installed classes.
Before timing/allocation comparison, match digests, RNG state, chains, repair calls
and callbacks. Wrapper dispatch/digest creation are timed. Restoration checks cover
assignment, factor degrees, costs, break/make counts, tabu/configuration state and
best-cost watermark. The probe measures prepared proposals, not full-solve quality.

## Validation

`./gradlew :klause-bench:test` checks the harness. Explicit `solveCampaign` runs,
`:klause:basisTrace --args="verify"` and `--args="replay"` are opt-in fixture
checks; numerical declines and missing inputs keep their normal semantics.
Run source-verifier regressions with its pinned requirements. Benchmarks, captures
and timing probes do not run as part of ordinary repository checks; follow the
[development validation policy](../docs/development.md#validation).
