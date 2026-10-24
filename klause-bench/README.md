# klause-bench

JVM catalog, corpus management and the subprocess runner klause-lab calls. This
README owns problem selection, solve records and the profile mains. Solver
contracts live in [docs](../docs/README.md). Committed fixture identities and
licenses live in the README beside each fixture directory, or in
[fixture provenance](#fixture-provenance) for fixtures without one.
Commands run from the repository root unless shown otherwise.

| Task | Read |
|---|---|
| Select problems or solve one | [Build](#build-and-run), [selection](#selection-and-named-sets), [corpus](#catalog-and-corpus) |
| Run experiments, comparisons or schedules | [Klause-lab](#klause-lab) |
| Read a solve record | [Provenance](#installed-build-provenance), [records](#saved-results), [witnesses](#witness-records) |
| Investigate cost | [Profiling](#profiling), [LP replay](#lp-capture-and-replay) |
| Review retained design decisions | [Design evidence](#design-evidence) |

## Build and run

```sh
./gradlew :klause-cli:installJvmDist :klause-bench:installDist
cd klause-bench
./build/install/klause-bench/bin/klause-bench select suite=core
./build/install/klause-bench/bin/klause-bench solve-one suite=dimacs-core problem=php4 out=/tmp/php4
```

`solve-one` runs the installed CLI as a subprocess; `installDist` alone leaves a
stale CLI binary. The installed launchers require JDK 25 or newer. Gradle provisions
its own compilation toolchain, so a shell whose default `java` is older fails at run
time with `UnsupportedClassVersionError`; point `JAVA_HOME` at the provisioned JDK,
for example `JAVA_HOME=$(ls -d ~/.gradle/jdks/*-25-*/ | head -1)`. The installed bench
launcher uses `klause-bench` as its working directory. From the source tree,
`./gradlew :klause-bench:bench --args="..."` runs the same commands and forwards
`-Dklause.*` properties to the bench JVM.

| Command | Purpose |
|---|---|
| `list [suite]` | List suites or problems |
| `select [filters]` | Emit instance identities as JSON lines; `features=true` adds structural features, `files=true` the resolved model, data and include paths |
| `solve-one suite=<id> problem=<name> out=<dir> [options]` | Solve one selected identity with klause-cli and save its record |
| `provenance out=<path>` | Capture installed-build and runtime identities |
| `corpus status`, `corpus gc`, `corpus compress [path]` | Inspect, evict or compress fetched collections |

`bench.profile.DivModConstructionProfile` times XCSP3 parsing and lowering without
solving. Use `sizes=1024,2048,4096,8192 repeats=3 warmups=1` for generated sources
that repeat each modular expression twice. Optional `suite=ID problem=NAME sha256=HEX`
adds one hash-pinned catalog input. Sources, per-iteration construction times,
compiled sizes, factor kinds and build/runtime identities stay under `$OUT`.
Compare the identical profile source and settings at both revisions on one host.
JFR construction timings are separate from uninstrumented, source-checked solve
controls and establish no solver throughput or verdict claim.

## Klause-lab

[Klause-lab](https://github.com/Eignex/klause-lab) owns everything that runs more than
one case: experiments and A/B comparisons, regression schedules, reference solving and
reference results, witness checking and profiling. It builds the Klause revisions an
experiment names, selects problems through `select` (with `files=true` and
`features=true`), and runs each problem/configuration/seed/repetition as one
`solve-one`, reading the record it writes. Reference solvers run in the lab itself, on
the files `select files=true` resolves. A profile job runs one class of
`com.eignex.klause.bench.profile` under JFR (see [Profiling](#profiling)).

Write experiments as described in the lab's
[experiments guide](https://github.com/Eignex/klause-lab/blob/main/deploy/SETUP.md#experiments)
and profile jobs as in its
[profile jobs guide](https://github.com/Eignex/klause-lab/blob/main/deploy/SETUP.md#profile-jobs).
Arm values are `solve-one` options; `param.<name>` becomes `--param <name>=<value>`.

## Selection and named sets

`sets/<name>.txt` fixes membership as one `<suite>/<problem>` identity per line,
matching `select`; `@<set>` includes another set and `#` starts a comment.
Focused sets cover the structural themes reported by `select features=true`:
open integers, linear reals, polynomial (nonlinear) reals, scheduling, routing, packing, other globals, MIP,
SAT, MaxSAT and PB. `sweep` includes them all. Material membership changes take
new names so results under one name stay comparable.

`nonlinear-integer-v1` fixes twelve self-authored SMT product/power sources covering
bounded integer factors, mixed real completion, lexical helpers and assumptions,
wide arithmetic and open integer proof limits. The matching
`smtlib-nonlinear-integer-v1` suite retains mathematical SAT/UNSAT expectations;
resource-limited or incomplete procedures can return UNKNOWN. The
[manifest](smoke-corpus/nonlinear-integer-v1/manifest.json) pins original hashes and
Apache-2.0 attribution.

`continuous-mip-v1` fixes 167 MIPLIB 2017 optimization sources with both integer
and continuous columns. It includes finite and open routes and selects every member
without a changing reference filter. It is separate from the smaller `mip` theme set.

`qfnra-core-v1` fixes 37 self-authored polynomial-real inputs; `qfnra-holdout-v1`
fixes 24 wider multivariate cases. The matching `smtlib-qfnra-core-v1` and
`smtlib-qfnra-holdout-v1` suites select the same identities without loading a
Klause model or checking reference availability. Families cover algebraic sections
and towers, strict/disequal and Boolean relations, shared/repeated roots, singular
and nullified polynomials, exact decimals and lexical SMT definitions/assumptions.
The [fixture manifest](smoke-corpus/qfnra-v1/manifest.json) binds every identity to
its original source SHA-256, license and mathematical expectation. Change names
for membership or byte changes; preserve version-1 selections.

`qfnra-partial-conflicts-v1` and its matching `smtlib-qfnra-partial-conflicts-v1`
suite fix 12 guarded numeric-conflict controls. Early and late guards with one,
two or four unrelated Boolean pairs distinguish pruning before region completion
from exclusion reuse across prefixes. SAT alternatives and complete UNSAT inputs
retain 33 real source coordinates. The [fixture notes](smoke-corpus/qfnra-partial-conflicts-v1/README.md)
and [manifest](smoke-corpus/qfnra-partial-conflicts-v1/manifest.json) define their
scope, mathematical expectations and pinned source bytes.

`qfnra-source-canonical-v1` selects two canonical negative-constant variants under
`smtlib-qfnra-source-canonical-v1`: `degeneracy/degree-drop` and
`degeneracy/equal-degree-common-root`. Their [manifest](smoke-corpus/qfnra-source-canonical-v1/manifest.json)
records original and variant source hashes and the `-1` to `(- 1)` transformation.
The original `qfnra-core-v1` identities remain in their frozen membership and
reference records. Z3 accepts their bare negative numerals; the dedicated polynomial
source parser treats those atoms as names and declines them. Report those source
load declines under their original identities. Use the distinct canonical set for
canonical source admission coverage, retaining unsuccessful original records.
An atom audit of all 66 version-1 core/holdout/extension sources found bare negative
numeric atoms only in these two sources. It does not certify full source admission.

`qfnra-extensions-v1` keeps five symbolic-division, mixed-integer, negative-power,
transcendental and multiple-query controls separate. Its catalog expectations are
unknown: these cases describe input boundaries rather than polynomial capability.
The fetched `smtlib-qfnra` suite uses the QF_NRA archive from Zenodo record 15493090
and preserves per-family source attribution/licenses without vendoring it. That
logic tag does not establish polynomial membership; the fetched suite is
unclassified and may include extensions. No Klause or reference verdict filters
its selection. Use `reference=any` in lab specs to preserve frozen membership.

| Filter | Meaning |
|---|---|
| `suite=a,b` | Restrict suites; `core` expands to the in-process core |
| `set=a,b` | Select whole named sets without default suite caps; `suite=` intersects membership |
| `kind=cop\|csp` | Classify optimization/satisfaction from source objective directives while filling family caps |
| `category=SAT,UNSAT,CSP,OPTIMIZATION,…` | Restrict categories |
| `tag=…`, `name=<glob>[,…]` | Tag membership or OR of substring/`*`-glob name patterns |
| `per-family=N`, `max=N`, `seed=N` | Cap and deterministically sample within each suite's own families; unset `per-family` uses the suite default |
| `balance=format` | Split `max` across present formats, redistributing unused shares |

`solve-one` takes the identity `select` printed plus these options:

| Option | Meaning |
|---|---|
| `out=<dir>` | Where the record and raw output go (required) |
| `timeout=<ms>` | Per-instance budget |
| `solver-seed=N` | CLI seed (`-r`); unset uses the harness's fixed seed |
| `engine=fixed\|cp\|mixed\|ls\|alns`, `processors=N` | Forward CLI `-e`/`-p`; unset engine uses the CLI default |
| `lp=off\|conservative\|default\|aggressive[±id…]` | Forward the CLI LP ceiling and technique deltas |
| `presolve=off\|conservative\|default\|aggressive[,±pass…]` | Forward the CLI presolve emphasis and pass deltas |
| `exact=true\|false` | Forward `--exact` |
| `param=key=value` | Repeatable CLI engine parameters; unknown/malformed keys fail at the CLI |

Other keys are ignored.

The `divmod` suite retains self-authored scalar, affine wide, equivalent linear and
joint-conflict sources, plus signed MiniZinc and XCSP3 inputs. The adjacent FlatZinc
fixture supports direct CLI captures. Klause-lab checks their recorded MiniZinc and
XCSP3 witnesses against the original sources.

`mps-linear-source-v1` retains self-authored decimal mixed rows, enabled/disabled
indicators, shared contradictions, constant objectives, bound closure, wide numbers
and duplicate decimal terms. Compare default and `exact=true` arms in a lab
experiment; this fixture suite requires hash-named source bytes.
Reference agreement does not verify an exported solver proof certificate.

`exact-globals` exercises MiniZinc's bin-packing and variable-count decompositions
and offset circuit, subcircuit, symmetric-permutation and inverse wrappers, together
with float array extrema. Each
model has finite-only, linear-real and polynomial-real variants with a witness,
refutation and minimum. Run the whole suite with `exact=true`; a successful
minimum is one. The CLI regressions cover the retained native global families and
set predicates separately, including fractional real objectives. Polynomial
optimization over a continuous coordinate runs in this suite; the focused JVM
matrix optimizes the finite global argument alongside exact polynomial completion.

## Catalog and corpus

The `continuous-arithmetic` suite contains synthetic MiniZinc fixed power, square, independent
product and product-chain controls with objectives attained at exactly representable
endpoints, plus a fixed-power satisfaction control. Compare executions in repeated matched default-grid lab runs with original
MiniZinc witness checking. Native grid exhaustion or an attained grid
objective supplies no original-source proof. Record compiler/build identities and
unchecked or declined runs separately before interpreting costs.

[Suites.kt](src/main/kotlin/com/eignex/klause/bench/catalog/Suites.kt) declares
vendored, generated, workspace and fetched inputs. Collection declarations own
license and redistribution policy. Legacy family sampling precedes resolution;
compatibility selection checks compilation while filling family caps.
MiniZinc Challenge instances with data use `year/family/data` keys.
Data-free instances use `year/family/model`, retaining the selected model's relative
path without `.mzn`; families retain `year/family`. Bare data-free family reference
keys cannot identify that model or be reused for its instance key. Discovery excludes
2026 families without `.dzn`/`.json` data and `2010/depot_placement`, whose legacy
annotation does not compile, before caps.
`mzn-bench-compatible-v1` is a separate installed-compiler compatibility selection.
It discovers depot-placement sources as well, then compiles the original model/data
with the installed MiniZinc compiler and Klause globals before filling family and
suite caps. Any source-level rejection, where the compiler exits nonzero with a
diagnostic, excludes only the identity checked, with a best-effort reason:
`type-error`, `syntax-error`, `index-set-mismatch`, `undefined-evaluation`,
`assertion`, `unsupported`, otherwise `compile-error`. Compiles exceeding
`klause.bench.mzn.timeoutSec` (default 60s) are excluded as `compile-timeout`, recorded
with `compilerTimeoutSec`. Compilable siblings remain eligible. Infrastructure faults
stop selection: a missing or unexecutable compiler, missing corpus files or solver
library, unreadable solver configuration, I/O errors, out-of-memory and compiler
deaths by signal; they cannot establish source incompatibility. Compilation uses seed 1 and never invokes a solver. Checks run only
on candidates reached while filling a family cap, after source-kind filtering;
seeded ordering remains deterministic. Catalogue `suite` selection applies
its admission policy before provider property/default caps as well. Raw identity
lookup (`uncapped`, named sets and `solve-one`) bypasses admission and provider
property caps for compatibility suites, retaining every original identity.

`select` emits excluded identities as JSON lines on stderr with the reason,
diagnostic, compiler exit, original model/data hashes and compiler/library identity.
Selected JSON lines carry `selectionPolicy=minizinc-compile-compatibility-v3` and
that identity. It includes the compiler version and binary hash, standard and
Klause library tree hashes, solver configuration hash and compiler seed. Selection
can change with any of these identities or source bytes; retain the actual selected
and excluded records together. This is compiler admission, not solver or proof
coverage. Fresh admission does not reuse compilation failures or prior compiler
results. Successful FlatZinc caches bind the compiler/library identity.

The original `mzn-bench` discovery and existing named sets retain their membership
policy and reference namespaces. Named sets bypass compatibility filtering, including
when they contain the versioned suite; `solve-one` still resolves every discoverable
identity and records compilation failure separately under `sourceCompilerIncompatible`
and `compilerExit`, preserving the legacy `loadError` diagnostic. Original source
hashes and compiler identity accompany those failures. Use a new set name when
freezing a compatibility selection; do not redraw an existing set or reinterpret
its old solver records.

Categories/formats, global/linear counts, Boolean-heavy structure and SMT logic
or MPS LP/MIP markers feed classification and theme sets.

`miplib2017-csp` removes free (`N`) rows and their column/RHS/range references.
Other coefficients, ranged sides, bounds and integrality remain source-identical.
Objective-only columns retain zero coefficients in a constraint row; models
without constraints receive a zero equality. Derived feasibility inputs have
separate content-addressed cache entries, reference namespaces and source hashes.

`satcomp-compact-v1` is a small, hard CNF set beside the mostly easy `satlib`: 40 SAT
Competition main-track instances (2022-2024), one per family, 20 SAT and 20 UNSAT, each of
which the organisers' Kissat entry decided in 5-120 s in the competition. Each is at most 2 MB
as `.cnf.xz`, 1M variables and 5M clauses: the download is 13 MB, the zstd cache 23 MB, and the
largest instance 18 MB plain. The
[pinned list](src/main/kotlin/com/eignex/klause/bench/catalog/SatCompCompact.kt) names each
instance by its [Global Benchmark Database](https://benchmark-database.de) content hash
(`gbdhash`: the MD5 of the clauses without comments, header or layout). The fetch downloads
`benchmark-database.de/file/<hash>` one instance at a time, rejects a mismatched hash and
stores it as `<family>/<file stem>`. Each problem expects the competition's verified result.
[`scripts/satcomp-compact.sh`](scripts/satcomp-compact.sh) redraws the list from GBD's
`meta`/`base` databases and the competitions' detailed results; 2025 publishes no
per-instance times. A redraw is a new suite and collection version, so problem names and
reference rows stay bound to the instances they named. The instances are the competitions'
research benchmarks; cite GBD (Iser and Jabs, SAT 2024) and the competition proceedings.
Most take a reference over 5 s, so lab selections of the suite use `reference=any`.

Fetched collections live in `~/.cache/klause-bench/corpus/`, respecting
`XDG_CACHE_HOME` or `-Dklause.bench.corpusCache`. The decimal 60 GB default cap is
configured by `-Dklause.bench.corpus.maxGb=N` or `KLAUSE_BENCH_CORPUS_MAX_GB=N`;
`0`/`off` disables eviction. Whole least-recently-used collections are evicted
before/after fetch. This JVM's used collections stay pinned; parallel bench processes
sharing a cache should prefetch or disable eviction, as the lab does. Metadata avoids
full tree scans; `.trash` renames make interrupted deletion resumable.

CNF, WCNF, OPB, WBO, XML, SMT-LIB and MPS collections can use `.zst`; MiniZinc
source/data and cloned collections stay plain. `zstd` must be on `PATH` and
CLI decompression is inside measured solve time. Hashes use decompressed bytes.
Compression writes beside the original, verifies SHA-256, then replaces it;
interrupted migration can resume.

## Fixture provenance

Fixtures with a directory README record their identities and licenses there:
the [smoke corpus](smoke-corpus/README.md) and the
[basis corpus](../klause/src/jvmTest/resources/basis-corpus/README.md). The
remaining committed fixtures are recorded here.

## Installed-build provenance

Records' build fingerprints hash every installed launcher/dependency jar, selected
Java runtime files, OS/architecture and inherited runtime options. Relative paths and
bytes identify the installation; launcher timestamps and source commits alone do not.
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

CLI presolve summaries remain in each `solve-one` record's `stats` map. Their [field contract](../docs/20-cli.md#statistics)
keeps phase work separate from native work and preserves empty and interrupted calls.

Ordinary subprocess records retain `stderr` as a prefix of at most 16 Ki characters,
with line endings normalized to newlines. `stderrComplete=true` means the drain
finished and the whole stream fits in that prefix. Null text or a false flag leaves
complete diagnostics unavailable, including older records. Successful
preparation-only CLI diagnostics are retained here alongside the separate raw stdout.
These diagnostics describe preparation and provide no source witness or proof credit.

Ordinary CLI statistics expose `openHintIndexAdmitted`, index decline categories,
and caller/component consultation and selection counters separately from
`openHintDraws` and `openHintSteered`. Component usage can be present without a
Boolean draw. A selected hint is a first-polarity preference, including agreement
with ordinary order; it is not a source witness or a committed-decision count.
Primitive and gate selections classify outer consultations, not recursive leaf
reads. Missing fields in older records are unavailable rather than observed zero.
Use the same record's route, index outcome and usage to establish feature coverage.
With absent `open-hint-flips`, one exact-linear completion owner can supply direct
primitive preferences from an existing partial point without source-clause indexing.
Index observations require actual construction through the explicit allowance. Explicit zero permits bounded construction observations
but no component steering; positive values allow indexed preferences after caller
or deferred hints decline. The optional Boolean draw requires this explicit
allowance. None of these paths renews numerical or indexing allowances; full
admission and lifetime contracts live in [search](../docs/08-search.md).

`openHintClauseIndexAdmitted` and `openHintClauseIndexDeclined` describe the optional
semantic index separately from the forward index. `openHintClauseConsulted`,
`openHintClauseSelected` and `openHintClauseDeclined` count actual two-hypothesis
consultations; declines include ties, missing point support and representation stops.
These observations retain both Boolean alternatives and certify no source verdict.

`solve-one` writes `<problem>.out` and `<problem>.json` under its `out=<dir>`.
Records retain verdict, objective direction, proof status,
statistics and portfolio attribution. Load failures get a record too:
`stats.unsupported` means declined input; `stats.loadError` means parsing/compilation
failed. Source model/data hashes and `finalWitness` (up to 8 MiB) are captured
outside subprocess timing. Missing captures remain absent; included MiniZinc
files are outside original model/data hashes. `compilerIdentity` records the compiler
and library identity for Klause MiniZinc solves and compilation failures.

Caught solve exceptions write `command=ERROR`, with nullable feasibility/objective
and no proof credit. `stats.errorClass` and `errorMessage` retain the exception;
`errorCause1Class`/`errorCause1Message` through `errorCause3Class`/`errorCause3Message`
retain its first three causes. Class names are capped at 256 UTF-16 characters and
messages at 1024 each; absent messages stay absent. A field exceeding its cap has
a matching `ClassTruncated=true` or `MessageTruncated=true` key. Further causes
set `errorCausesTruncated=depth`; repeated throwable identities set it to `cycle`.
Stack traces and suppressed exceptions are not stored. Inspect these durable JSON
fields even when stdout or job logs have been trimmed.

ERROR records preserve original source hashes, installed-build provenance/fingerprint
and MiniZinc compiler identity captured before the failing operation. `gitSha`
identifies the harness checkout, separately from installed bytes. A failed identity
capture leaves that identity unavailable and retains the capture exception; the catch
path does not recapture changed inputs or installations.
An undecided returned result keeps its ordinary command and statistics, without
caught-exception fields. Legacy ERROR records with empty statistics remain readable;
their missing exception text and installed-build/source identities cannot be recovered
from the record. Added diagnostics use the existing string-valued `stats` map.

| Field/channel | Contract |
|---|---|
| `elapsedMs` | Subprocess launch through output consumption; nullable for legacy/unlaunched runs |
| `stats.frontendLoadMs` | Inclusive frontend parse, lowering, routing and solvable construction; excludes process startup |
| `stats.frontendRoutingMs`, `frontendRoutingOutcome` | Measured frontend routing time and outcome; absent when unmeasured; nested within frontend load |
| `stats.solvePipeline`, `declaredTheoryRoute`, `reportedBackend` | Selected CLI solve entry point, pre-preparation theory declaration, and final backend snapshot; distinct stages |
| `stats.nativeCpPropagationWork`, `nativeCpRootPropagationWork`, `nativeCpPropagationMs`, `nativeCpRootPropagationMs` | Existing native fixpoint aggregates when native measurement is evidenced; root is a subset, not open-theory root cost |
| `stats.nraDyadicAttempts`, `nraDyadicBases`, `nraCoupledWorkDeclines`, `nraDyadicPreExtractionDeclines` | Observed eligible attempts, complete bases, private coupled-work stops and their pre-extraction subset; absent without producer observation, distinct from source truth or inner elapsed cost |
| `timeToBestMs`, `timeToFirstFeasibleMs` | Nullable without a witness; distinct from total elapsed time |
| `exactObjective` | Canonical integer/reduced rational text; authoritative for comparisons and references |
| `objective` | Legacy approximate display; may be null outside binary64 range |
| `optimizationStatus` | SMT terminal status in `stats`; optimum credit needs `sat`, a valid exact objective and explicit `optimal` |
| `%%%klause-arm:` | One attribution line per installed incumbent under `-s` |
| `objective=<Long>` | Exact discrete attribution value, preserving integers beyond `2^53` |
| `continuousObjective=<Double>` | Whole model-oriented objective when continuous columns contribute; absence is not a zero offset |
| `lpPhase_<phase>_<route>_Calls`, `_Nanos`, outcomes | Completed attempts, including declines; acceptance includes dual recovery/cleanup and overlaps other phases |
| `lpPhase_PRIMAL_ROUNDING_ROOT`, `lpPhase_PRIMAL_PUMP_ROUND_ROOT`, `lpPhase_PRIMAL_PROPOSAL_ROOT` | Discrete proposals and completion/admission outcomes; inclusive timing overlaps solve and verification phases |
| `lpPhase_PRIMAL_LP_PRIMAL_ROOT`, `lpPhase_PRIMAL_LP_DUAL_ROOT` | Root LP point availability and termination before rounding; stopped iterates supply no primal target |
| `armFailure.<label>` | Bounded CLI JSON diagnostic; a retired arm need not invalidate a sibling verdict |

Exact values take precedence over numeric fallbacks; malformed exact values earn
no objective credit. `best-found`, plain `sat` and unbounded witnesses earn no
optimality credit; an unbounded record's objective belongs to its feasible point.
The exact ladder's phase timing is inclusive. Missing metrics remain missing,
and work/pivots do not substitute for elapsed time.
Frontend load and routing times overlap, as do native propagation totals and their
root subsets. Native timing aggregates can include several workers and private
preparation, so subtracting them from invocation duration does not recover theory
time. Missing startup, open-theory root and open-theory propagation clocks remain
unavailable. A declared or reported theory label alone does not establish execution;
preparation can stop before its first check. See the [CLI statistics scopes](../docs/20-cli.md)
and [search contract](../docs/09-learning.md#statistics).

Exact-linear records distinguish `smtRealGraphOpenings`, completed primitive
table captures (`smtGraphPointHintCaptures`, `smtLpPointHintCaptures`) and
`smtDefaultGraphHintReleases`. The capture counters name the existing point's
producer path, including LP fallback in a graph-first opening. A completed table
can contain only declined preferences. Release counts a live default consultation,
not avoided registration or capture work. These observations add no numerical
invocation and establish neither a timing cause nor a whole-source witness gain.

### Subprocess exit records

Each returned subprocess record retains the actual child exit in `stats.exit`,
including a nonzero exit after a verdict. Lab command exits belong to the containing
harness process. Older records without this field leave the child exit unavailable;
a witness or proof marker does not establish a zero process exit.

## Witness records

klause-bench solves and records; it checks no witness itself. Every record says
`validationPolicy=reported-result-v1`: its feasibility, objective and proof claims are
the solver's own report. [Klause-lab](https://github.com/Eignex/klause-lab/blob/main/deploy/SETUP.md#source-witness-validation)
checks witnesses against the original sources with established external checkers
(the official XCSP3 solution checker, the MIPLIB solchecker, MiniZinc with Gecode and
Dolmen), reading `finalWitness`, `exactObjective`, `objective` and `sourceHashes`
from the record.

`finalWitness` keeps the final complete candidate as the solver printed it:

| Format | `finalWitness` |
|---|---|
| MiniZinc | The last complete DZN solution from its first assignment, then its `% klause-exact:`, `% klause-coordinate:` and `% klause-float-` lines; a Klause solution at model level (below) |
| XCSP3 | The last complete `<instantiation>`, prefixed once with `v ` |
| SMT-LIB | The last complete model, from its `(` line to its `)` line, with newline line endings |
| MPS, OPB, DIMACS, WCNF | The last `v ` line |

Klause solves a MiniZinc model's compiled FlatZinc, so its own output names FlatZinc
variables (`X_INTRODUCED_*`) and prints enums as integers. The bench passes the final
solution through the model's compiled output mapping (`minizinc --ozn-file`, the
`.ozn` compiled beside the FlatZinc), so `finalWitness` assigns the model's output
variables, enum values by name, and can be read as extra data for the model.
`stats.finalWitnessLevel` is `model`; a solution the mapping cannot read keeps its
FlatZinc-level text with `finalWitnessLevel=flatzinc` and the reason under
`stats.finalWitnessMappingError`.

Authoritative rational coordinates travel as `% klause-exact: name = value;`
comments beside decimal assignments. Mixed exact/grid witnesses use
`% klause-coordinate:` with `% klause-float-component:` provenance; the grid
coordinate is the exact binary64 value of the decoded point, not an exact source
claim. A Klause run of a MiniZinc model with float variables records
`stats.floatApproximation`, which is `true` when Klause solves it on a binary64
grid; such claims are approximations. See [input semantics](../docs/03-input-semantics.md).

## LP capture and replay

These opt-in tools isolate numerical basis operations.
Capture records a bounded operation stream; verification checks its identities
and residuals; replay or paired comparisons measure that recorded work. A kernel
trace does not establish correctness or performance of an entire source solve.

Use the basis corpus for numerical regressions and dense comparisons for basis selection.
[LP architecture](../docs/17-linear-programming.md) owns implementation contracts; full solve
measurements run as klause-lab experiments.

### Basis corpus replay

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
decline instead of being silently selected or relaxed. See the [basis corpus README](../klause/src/jvmTest/resources/basis-corpus/README.md)
for source identities, redistribution terms, hashes and exclusions.

### Dense selection comparison

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

## Profiling

`ExactLinearScoringProfile` compares identical dyadic mixed-row candidate sequences
at widths 8/64/512/4096, including committed choices and a fresh final score.
Three warmup batches precede five measured batches of 4096 primitive candidates
and 256 commits. It reports score/selection hashes, scoring time and Java thread
allocation. Run the same profile source at both revisions through a bounded lab
profile job. These synthetic JFR measurements exclude construction and source
acceptance; ordinary cold CLI comparisons measure whole-search effects.

`NumericalResidualProfile` accepts `request64=<base64 JSON array>` of up to eight
`{suite, problem, sha256}` MPS sources. It checks original bytes, observes a direct
satOptimized worker at seed 3 for two seconds, and retains actual refactor-policy
triggers, basis phase timings and up to two immutable exact residual snapshots.
The profile uses reflection over existing private seams; observations add overhead.
Its exact leaf acceptance differs from ordinary configured-tolerance CLI execution.
It establishes neither a source result nor default portfolio equivalence.
An entry with `root=true` captures the existing production relaxation after root
propagation, without traversal or optional presolve. That root projection is labelled
separately from an observed continuous leaf and retains its normalized numerical
authority, rather than asserting original MPS decimal equivalence.
Catalog roots use the baked in-process problem. Optional `unbaked=true` compiles the
original bytes directly, matching the source-root representation used by basis traces;
the two representations can have different dimensions and are separate controls.
Self-authored conditioning controls can supply `{mps64, problem, sha256, root}`;
the bounded request supplies original MPS bytes, which receive the same hash check
and numerical compilation as catalog inputs. Optional `exact=true` uses zero source
feasibility tolerance for that supplied control, retaining its distinct semantics.
`{control: "near-dependent-40"}` captures a self-authored LP model with twenty
independent two-row blocks. Each block has continuous bounds `[0, 2]`, matrix
`[[1000000, 1000000], [1000000, 1000001]]`, right-hand side `[2000000, 2000001]`,
and unit minimization costs. Its unique point is all ones. This model bypasses MPS
compilation; recorded accepted-update pivot spread establishes whether replay visits
the intended near-dependent basis.

`NumericalResidualReplay` accepts an array of
`{job, source, residual, sha256, dense}` capture references through the same argument.
It checks retained local-job bytes and replays each state through the existing scoped
solver and production certification, comparing cold and retained owners. Each solve
has two seconds, 4096 pivots and 1.2 billion work units, with pricing tie seed 3.
Optional `workLimit` records an explicit allowance up to that default; zero preserves
the source leaf's unbounded-work setting while retaining the containing cancellation.
One warmup and three measured rounds disable per-operation observation; a separate
observed round retains phase and certification details. Lab JFR remains enabled.
Optional `observedOnly=true` retains the warmup and observed rounds for a subsequent
attribution question. It supplies no observation-disabled timing sample. Observed
solve records aggregate actual RHS/output support, density hints, sparse triangular
selection and visits separately for FTRAN and BTRAN.
Optional `auditBasis=true` adds at most 128 source-matrix solve residuals and 16
current upper-factor slice samples to the observed round. Each slice records its
window, dimension, support checks and up to 64 entries. Auditing runs outside
per-operation timing; its overhead still enters the observed lifecycle total.
Owner construction, preparation, solve, certification and close enter lifecycle
timing; result serialization follows each timed call.
`dense=true` pairs sparse construction with the existing dense LU builder on bases
of order at most 64, recording input dimension and density together. This diagnostic
does not change production dense selection. Captures contain no original warm factors;
replay and instrumented timings are separate from whole-source measurements.
With `dense=false`, optional `synthetic="compare"` pairs cumulative-work
refactorization enabled and disabled. Residual, fill, solve-work
growth, backend requests and hard update limits retain their production settings.
Ordinary solves disable cumulative-work refactorization. The diagnostic records
each intervention separately from the dense comparison.
Optional `entry="session"` uses the retained solve-session consumer used by real
leaf completion; the default scoped entry prepares factors before its first query.
These are distinct numerical openings. The session entry excludes caller model
projection from lifecycle timing and retains its default pivot allowance. Both
entries reuse the existing exact-state shape inspector outside timed calls.
`entry="cursor"` uses that session's suspended solve with an optional `quantum`
of modeled floating work per advance; zero consumes it without yielding. The hard
invocation `workLimit` remains separate. Observed rounds record advance times,
checkpoint work deltas and cumulative numerical metrics, with a bounded trace.
Certification work remains in its own observer records. Compare session, unlimited
cursor and partitioned cursor with identical backend settings and allowances.

Profile mains live in `com.eignex.klause.bench.profile` and run as a `lab profile`
job: the lab builds the named commit and runs the class once under JFR, keeping what
it writes to `$OUT` (see the lab's
[profile jobs guide](https://github.com/Eignex/klause-lab/blob/main/deploy/SETUP.md#profile-jobs)).
The lab runs no shell scripts, so a new measurement is a class in that package.

`SourceStageProfile` accepts `request64=<base64 JSON array>` for stage attribution.
A source request has `suite`, `problem`, decoded-source `sha256`, `engine`
(`default`, `cp`, or `ls`) and `exact`. It profiles the installed CLI for ten seconds
at seed 3 with one active processor, a 3 GiB heap, Serial GC and debug statistics,
then runs the existing JFR phase/caller analyzer. It retains the command, source,
streams and recording. These instrumented runs diagnose stages without a throughput
or source-verdict claim. Three spaced `jcmd Thread.print` snapshots provide a
second view when JFR sampling is sparse; attach can pause the measured process.
A `{job, sha256}` request instead checks and summarizes an
existing profile job's `cases/0/out/profile.jfr` without executing a solver. An
optional `recording` selects `cli.jfr` or `profile.jfr`, optionally beneath a
single output index from 0 through 7. The summary includes runtime event durations
and native-method samples alongside the execution samples and allocations. Use a
local profile job with an explicit `profile.runTimeoutSec` for either form.

`CoefficientLiftingProfile` records Java thread allocations for the coefficient
strengthening pass on unchanged and productive 64-, 512- and 4096-term inequalities,
with LE and GE inputs. Model construction and root propagation precede warmup and
measurement; the profile verifies that the root retains each declared binary range.
Five batches of 256 calls follow 512 warmup calls per shape. Run the same profile
at each comparison revision; its output includes installed-build and JVM identities.
It measures pass allocations, without a complete-solve performance claim.

[SolveTiming](src/main/kotlin/com/eignex/klause/bench/profile/SolveTiming.kt) is the template for
a new profile main. It solves one catalog problem `repeats` times in-process and writes each run's
wall time and verdict to `$OUT/timings.json`; without arguments it times `opb-core`
`sporttournament06` three times:

```bash
./deploy/lab profile com.eignex.klause.bench.profile.SolveTiming main "solve timing" \
  suite=dimacs-core problem=random3sat-50-200 repeats=3 timeout=10000
```

`LinearRootOpeningProfile` inspects an original catalog source's direct-library root
opening without advancing its traversal. Pass `suite=ID problem=NAME sha256=HEX`; it
verifies and saves the decoded source bytes, then runs with a ten-second opening
deadline and seed 1 in a one-processor child JVM. Parsing is timed separately before
that deadline. The output records the raw decided reason or live root counters,
deadline timing, runtime/build identity and child command, streams, exit and transport
status. This diagnostic claims neither CLI routing equivalence nor a source verdict.
With `inspect=disjunction-names` it also replays predicate registration from the
cached preparation fragment in a fresh registry, within the original deadline's
remaining time, recording the first refused row and any existing complementary names.
That is isolated naming demand, not the opening's refusal cause.

With `inspect=partial-arithmetic`, the profile pauses at the eighth nonroot
consistent boundary and opens the source twice at seed 1. Each opening has a
ten-second deadline; each read-only snapshot has a separate ten-second allowance.
The snapshots retain source names and private slots, ordered reconstruction steps,
exact rows, signed guards and reasons, private choices, bound witnesses and any
partial point. Shared premise chains retain graph references instead of repeated
tree expansion. The existing exact LP capture codec preserves matrix and scoped
assertions in `prefix-N.exact`; decoding and encoding must preserve its bytes.
`replay.json` compares canonical state snapshots from the two fresh openings.
This is a direct-library diagnostic, not default CLI or source-verdict evidence.
`batch64=<base64 JSON array of argument arrays>` handles up to four sources in
numbered directories without changing their individual limits.
`inspect=partial-implication` preserves the same two initial snapshots, then
compares eight further boundary visits with and without an explained interval
probe. The probe uses exact active column bounds and shared reasons; it enables no
default solver policy. Probe time, charged work, deductions and subsequent search
progress are separate observations, not source-verdict or complete-solve speed claims.
`inspect=partial-propagation` compares a plain opening with the opt-in producer
enabled after root initialization and before the first decision. The producer
uses a cached list of eligible guards and returns to the shared fixpoint before
an LP query when it publishes a consequence. Its snapshots represent different
interventions; the report does not claim they are identical replay states.
`inspect=partial-propagation-cache` compares that producer without and with
unchanged-input decline reuse. It requires identical source/search snapshots
before and after continuation, and records arithmetic evaluations and reuse
separately from candidate visits and elapsed traversal time.
`inspect=partial-propagation-cached` compares ordinary propagation with the cached
producer. Inspection records producer, source assertion and numerical-query time
from after ordinary root initialization. These diagnostic clocks are disabled in
default solver execution; they exclude root-opening and snapshot costs.

`inspect=partial-source-batching` compares individual source edits with positive-depth
batching at the same revision, enabling the cached partial producer in both openings.
`inspect=partial-source-batching-plain` makes that comparison under ordinary propagation.
Both require identical source/search snapshots at boundaries eight and sixteen.
Post-root diagnostic counters separate definition-appending time and attempts from
bound-assertion time, calls and strengthened sides. Bound time covers the existing
snapshot/adoption path; preparation of normalized terms remains in the containing
source-assertion time. Calls include redundant individual assertions; strengthened
sides retain the per-bound work debit even when one call shares their snapshot.
These clocks are opt-in and establish neither ordinary CLI timing nor source verdicts.

[Lab 7956](https://lab.eignex.com/jobs/7956) preserves both boundary snapshots and
continuations byte for byte, including the PWD/myget states retained by Lab 7859.
PWD retains 4,024 strengthened sides and 1,107 definition appends while source-bound
calls fall from 5,213 to 2,216. The two warmed pairs reduce bound-edit time from
333–366 ms to 112–114 ms and containing source assertion from 1.47–1.63 s to
1.27–1.29 s. Definition appends still take 1.09–1.14 s in the batched openings.
Myget retains its 28 sides, 11 appends and unchanged frontier. These same-head JFR
diagnostics support sharing bound edits; they establish no source verdict or
default-policy solve speedup, and the broad partial producer remains opt-in.

The [partial arithmetic investigation](https://github.com/Eignex/klause/issues/3426#issuecomment-6375588869)
retains matching source/search states for cached and uncached PWD and myget prefixes.
PWD publishes 37 explained guards over sixteen boundary visits; decline reuse reduces
arithmetic evaluations from 67,100 to 3,656 while preserving those states. Myget has no
deductions and reduces evaluations from 24,007 to 1,213. These diagnostic results support
the opt-in mechanism; they do not establish a default-policy or source-verdict improvement.

`LinearSourceShape` accepts `suite=ID problem=NAME sha256=HEX` and retains the
hash-matched original, build identity, declaration/lowered dimensions, factor kinds
and exact-row predicate counts. Predicate identity removes only the activator before
using `ExactLinear` structural keys; it does not reason about signs, implications or
source satisfiability. Parsing has a separate ten-second deadline. It opens no solver
and supplies no route or verdict claim. For up to eight sources,
`batch64=<base64 JSON array of argument arrays>` retains each inspection in a
numbered directory.

`ExactFeasibilityCapture` takes the same source arguments and observes one direct
complete-worker search at P1/seed3 under a ten-second containing deadline. It wraps
the existing certification observer and retains the first complete DIMENSION
observation without changing its verdict or limits. `complete.klexact` uses the
engine's existing exact-state codec, with current bounds, origins, scoped rows and
witness ids; `premises.json` binds active sides to source-atom antecedents and their
observed truth. The capture includes source/build/runtime hashes, Booleans, failing
dense guards and free versus constrained logical-row counts. Encoding and codec
round-trip checks follow traversal; observer cost is recorded separately. This is
a diagnostic opening, not the CLI/default portfolio or an independent source proof.
Counts of removable-looking rows do not establish that a projected system fits.
With `inspectJob=ID captureSha256=HEX`, the same profile reads its retained
`complete.klexact` from a prior local lab job, checks its hash, reports fixed
structural coordinates and row components, then uses the existing `LpExactReplay`
for a cold numerical replay. It also invokes existing exact continuation from a
natural logical basis under a ten-second cancellation bound, reporting current
full-verifier acceptance and cumulative costs. Constant-row counts do not certify feasibility, and
cold replay does not reproduce the original warm factors, path or elapsed cost.

### Polynomial phases and witness checkpoints

`com.eignex.klause.bench.profile.PolynomialWitnessProbeProfile` diagnoses the bounded
root witness producer before CAD. Its single `request64=<base64>` argument encodes
up to eight `{id, base64, sha256}` original SMT sources. The decoded request is bounded
to 48 KiB and each unchanged source to 32 KiB. Submit it as a `lab profile` at
a pinned revision. The output retains original bytes, request, installed provenance
and revision, including installed harness hashes and actual JVM options/core count,
plus alternating plain/observed producer records.

The JVM-only observer reflects the existing root query and native owner. It records
the stack and cumulative charge at the existing coupled-work stop checkpoint without
returning cancellation or adding native charges. An observing caller meter also groups
every existing native charge by its engine stack; the attributed sum must match the
native snapshot. Stack collection adds diagnostic overhead, not native allowance.
Both modes use the same source,
fixings, default representation caps and a 60-second containing deadline. Two query
checkpoints run normalization and bounded probing; no projection or lifting follows.
All four native snapshots and outcome classes must agree. A pending producer supplies
no verdict; a component witness still needs ordinary pipeline admission and independent
original-source checking. Instrumented component timings establish no default-route
performance gain. Field/method incompatibility fails the diagnostic explicitly.

`com.eignex.klause.bench.profile.PolynomialPhaseProfile` follows a direct polynomial
query through its containing stop. Its `request64=<base64>` argument encodes up to
18 `{suite, problem, sha256}` catalog identities, with at most 48 KiB of encoded
request and 1 MiB per original source. It requires the frozen original hash before
parsing and retains those bytes. Submit it as a Lab profile job without host or
machine placement overrides. The Lab's documented `profile.runTimeoutSec` covers
active setup, compilation, execution and cleanup; `base.timeout` supplies the
separate command allowance.
The measured child uses one active processor, Serial GC and a 3 GiB heap, retaining
its command, streams, actual exit, installed CLI/harness/runtime hashes and options.

Each direct query uses default polynomial representation limits, unlimited native
work and a 60-second containing deadline. Optional `timeoutMs=<positive milliseconds>`
selects the containing deadline for source-matched diagnostics at another budget;
the saved settings retain the actual value. It does not change native or representation
limits. The separate gate inspection uses its own deadline of the same duration. The diagnostic records normalization and
bounded-probe returns, completed projection and the terminal result/reason/resource.
At the first containing deadline poll it captures the operation stack. Its constant
caller meter must exactly match the native query snapshot, excluding separately
reported parsing charges. A separate bounded normalization records conjunctive
guidance admission and normalized node kinds; its work does not enter the query.
No observer supplies a candidate, changes a limit or changes a cancellation result.
Private optional-producer declines remain distinct from containing termination.
Missing deadline stacks remain absent, including native work/resource stops.

These traces concern the direct arithmetic query, excluding pipeline scheduling,
frontend rendering and source publication. A rejected bounded producer has no
refutation authority. A direct witness needs ordinary pipeline admission and
independent full-source checking before credit. Timed traces need not have identical
work prefixes, and their component timings establish no default-route speedup.

Direct query checkpoints retain up to 128 stage observations plus the terminal
observation. When supplied by the selected revision, `progress` records committed
root snapshots, their latest atom/elimination counts, charged root/search work and
first regional entry. A missing progress field is unavailable. These scalar
observations retain no uncommitted formula or partial reconstruction.

Partial-state sign checking exposes exact check/conflict counts and numeric
exclusion uses/reuses through the same progress record. Retained sign-state
observations also count system builds, extension checks and predicates submitted
to checks. These counters accumulate actual attempts across search snapshots.
A supported conflict
observer retains the first completed conflict's full premise count and a preview
of at most 16 predicates, each limited to 512 characters. Coordinate identifiers
belong to the measured query. Formatting visits at most eight terms and eight
coordinates per term; coefficients wider than 96 bits are abbreviated. The completeness flag
distinguishes a full preview from truncated diagnostic text; neither is an exported
proof or source verdict. A missing observer or an unobserved conflict leaves these
fields absent. Interrupted and representation-declined checks learn no exclusion.

`prepared=true` selects the unconditioned polynomial formula from
`SourceSetup.prepare` and records its total query dimensions. The same containing
deadline includes parsing and source preparation; their charges stay separate
from native query work. `prepared-trace.json` retains this diagnostic instead of
the raw-source gate/query pair. CP-root pins and portfolio execution are absent,
so these are neither default-route timings nor published source verdicts. A
different prepared owner yields `NotPolynomial`. This option is separate from
`pipeline=true|both`.
Use the alternating bounded-probe profile for deterministic observed/plain parity;
pair phase attribution with source-matched ordinary CLI records.

Add `pipeline=true` after the request argument to observe source admission and the same
MIXED/P1/seed3 satisfaction execution used by default CLI polynomial requests.
That mode records the common termination reason, native/resource and search
statistics, caller charges and the first containing deadline stack. It also records
declared Boolean/integer/real counts, the retained factor model's coordinate and
factor counts, and the polynomial request's total Boolean/real counts when present.
These are separate representations; input declarations cannot stand in for the
arithmetic query's dimensions. Polynomial totals are read from the existing captured
source without modifying it; incompatible field layouts fail the diagnostic.
Linear controls retain their selected linear execution, result and search statistics,
with no polynomial work attribution. Refutations retain declaration dimensions;
absent polynomial query dimensions remain absent. Its single
deadline, set by `timeoutMs` or the 60-second default, covers admission, preparation
and execution. It records a
pipeline trace instead of direct-query checkpoints or a separate gate inspection.
Other execution families fail explicitly.
Pipeline-only `repeats=1..9` retains independent invocations as `pipeline-N.json`
when more than one repeat is requested. Deadline latency is recorded separately at
the first expired poll and at pipeline return. Early returns have zero return
overrun and no expired-poll observation. Stack capture and diagnostic result
construction lie between these observations; the return overrun includes their
cost and is not an uninstrumented CLI latency measurement.
Pipeline caller charges include source/search preparation and are separate from
the native arithmetic snapshot. This mode does not render a CLI model or independently
validate a source witness. Keep ordinary CLI records for publication/control evidence.

`pipeline=both` records the pipeline trace and the separate direct gate/query traces
for every source in the same profile invocation. Each producer retains its existing
containing deadline; the complete paired question must fit one whole profile allowance.
Use the captured dimensions, internal counters and terminal stacks to distinguish
representations and stopped operations. Paired diagnostic timings establish no
default-route performance gain, and direct results supply no shared-source acceptance.

The final-root handoff policy at `d977903` has a source-matched sequencing result
in [run 7838](https://lab.eignex.com/jobs/7838). Across three 10-second Mac/aarch64,
P1, seed-3 repeats, sorting completes 110–180 projection polynomials and 541–688
cells; kissing completes 144 polynomials and 5207–5258 cells. Root-first completes
neither counter on either original, and both originals remain UNKNOWN. Counters
accumulate actual attempts; replacing search with a finished root formula can
repeat work. Intermediate commits retain the cursor, and the completed-root
handoff preserves the existing fifth-order transition behavior.

The tower SAT control increases from 14,795 to 28,607 native units; all nine SAT
models in that comparison pass the Lab source checker. The Boolean UNSAT control
matches root-first's 76,620 units and 3 polynomials/10 cells.
[Regression run 7849](https://lab.eignex.com/jobs/7849) preserves all 68 paired
outcomes across the complete core/heldout sets and additional original, mixed and
Boolean controls. Each arm has 37 independently accepted SAT models, one unchecked
DIMACS SAT and one SAT whose power syntax the checker cannot parse. Native UNSAT
claims have no independent certificates. Single regression samples establish no
timing comparison. Small-case costs can rise substantially: strict-root
contradiction spends 826→23,469 units and quartic no-real-root 1462→16,929.

### MPS LP admission capture

`bench.profile.MpsLpAdmission` takes `batch64=<base64 JSON array of argument arrays>`
with one to sixteen `["suite=ID", "problem=NAME", "sha256=HEX"]` requests. It binds
hash-matched original MPS bytes through numerical source preparation and finite
presolve with seed 3, a 1.65-billion work allowance and a twenty-second containing
deadline. The report records those diagnostic preparation controls, resolved
LP admission predicates, selected mixed/CP recipes and each backtrack recipe's
resolved node bounding. It then assembles full and objective-cone root relaxations
without solving either. Preparation can query numerical bounds.

Each result distinguishes emitted rows, structural columns, stored nonzeros,
connected components and isolated columns from the admission proxy. Reflection
checks internal constructor arity and fails on a mismatch. Run as a bounded lab
profile at a pushed revision; retain its original bytes, installed-build/runtime
identity and actual platform. These instrumented snapshots establish neither
worker scheduling, solve cost, useful deductions nor a source verdict; pair them
with ordinary records' actual recipe and segment observations.

Add `queries=true` after `batch64=...` to compare bounded root queries on each
prepared source. The profile keeps the existing caller override `bounding=true`
and governor defaults, depth zero, no primal probe or cuts. Full cold owners,
full retained-owner consultations and cone cold owners each make three paired
checked-root-bound/node-prune consultations, with a fresh equal root session for
each node query. Each mode has a thirty-second containing deadline, each
consultation at most ten seconds, and one cumulative governor per owner. Adaptive
work and actual warm-start counters remain visible; retained owners do not imply
retained factors or equal work budgets.

`queries.jsonl` flushes construction, checked-bound, node-session/prune and close
timings and current-thread allocations independently, with before/after work,
pivots and certifier counters. The checked bound comes from the existing certified
root-bound operation; a declined/nonfinite bound is null. Infinite cutoffs permit
infeasibility prunes only. These are native compiled-LP observations, not
configured-tolerance MPS source verdicts or proof validation. Current-thread
allocations omit native memory and physical RSS; the report retains the actual
profile JVM heap ceiling. Use maintained ordinary resource captures and source
validation for end-to-end qualification. No production admission threshold changes.

The `prepared-cold` and `prepared-retained` modes separate root source preparation
from adaptive floating-query work under the existing cumulative LP allowance.
Preparation is charged before solving, including declined edits. These opt-in
internal modes retain the same deadlines, source authority and query caps; ordinary
solver callers keep their existing policy. Compare them with the full modes using
the reported preparation allowance, reservations, owners and actual work rather
than treating extra construction work as a matched solve budget.

`prepared-session` holds one root session and its existing scoped numerical owner.
It compares every Boolean pin and immutable integer-domain identity with the initial root
after each consultation and stops on a changed root, a certified prune,
cancellation or the existing governor's demotion/exhaustion. Its consultation count
is determined by those boundaries, rather than three repeats. This mode tests
retained partial progress without inheriting a changed CP problem; float termination
and cumulative preparation/work counters expose retries and ownership reuse.

`feedback-cold` and `feedback-session` add work-stop feedback to that comparison.
Only an observed work termination with pivots and no point grows the existing
adaptive request; its ceiling, cumulative governor and deadlines still apply.
The report names the internal root policy explicitly. A missing point is neither
an infeasibility proof nor evidence of a numerical failure.

`query-session` uses only the retained node-query sequence, with no repeated
standalone checked-root probes spending its containing allowance. Returned guides
use the existing bound certifier and objective projection; the report marks the
bound origin and the skipped standalone phase. Compare this sequence when judging
the query policy's cost and deductions, keeping the paired modes' diagnostic work
separate.

`checkpoint-session` uses the same retained root and governor with a numerical
cursor. Query allowances pause at iteration boundaries instead of terminating the
operation; cumulative limits and changed-authority invalidation still apply.
`floatPaused` distinguishes a pause from a completed work stop. No paused point or
proof is consumed, and work/pivot counters sum disjoint advance deltas.

Use `queries=checkpoint` instead of `queries=true` for the numerical control: one
immutable full prepared root, production pricing seed 3, 150M floating work units
per arm, uninterrupted, repeatedly stopped and checkpointed execution. Round zero
warms up and round one reverses arm order. The report separates floating work,
scaling preparation and observer counters; full ordered headings stay beside each
checkpoint. Per-advance timing/allocation excludes report formatting and source
projection. Pauses retain the artificial-phase continuation without publishing a
result. The same cumulative limit can overshoot by one atomic numerical operation;
compare actual work and final headings, rather than aggregate repeated pivots.
Node-only same-root modes stop when a completed floating solve supplies a checked
bound; this is neither a source optimum claim nor an exact attainment check.

The frozen `cap6000`, `rentacar` and `misc04inf` comparison rejects an automatic
admission bypass based on sparsity or successful preparation alone
([diagnosis](https://lab.eignex.com/jobs/7846),
[node-only qualification](https://lab.eignex.com/jobs/7903)). Their emitted
rows/columns/nonzeros are 2171/6000/18243, 15801/17818/100772 and
6305/9733/43195. The latter two exceed the admission proxy ceiling; their dominant
components retain almost all rows and columns. At that diagnosis revision the
objective cone emits zero rows on all three, so its box bounds do not replace the
full relaxation.

Separating preparation and retaining one root owner clears the construction
barrier. With work-stop feedback and no standalone diagnostic probes, each large
model exhausts the existing cumulative allowance after eight node solves, all
ending on work without a guide, checked bound, fixing or prune. Node work is
149.47M/149.94M, with one preparation and one warm hit in eight attempts each;
retaining an owner does not establish retained factor progress. The control
reaches the checked native lower bound -2555534 before work exhaustion.
The large node sequences take 0.228/0.113 s and allocate 0.665/0.314 GB on the
measured thread, under the profile's actual 6 GiB maximum heap on Mac/aarch64,
Java 25.0.4.1 with JFR. These phase observations establish neither physical peak
memory nor an end-to-end speedup.

[Ordinary controls](https://lab.eignex.com/jobs/7850) keep first feasibility,
incumbent quality and proof status separate: all nine control witnesses pass
original-MPS checking, all remain unproven, and the eighteen large-model runs
return unknown without witnesses. Ordinary admission stays unchanged; the
internal bounded comparison remains available. This decision concerns the tested
root-query policies and resources, not every LP representation or numerical
kernel.

[Cone fidelity capture 7975](https://lab.eignex.com/jobs/7975), at `bf5a595916`,
retains the same three source hashes and preparation/query controls. Closure through
exact rows and real coordinates emits 2171/6000/18243 rows/columns/nonzeros on
cap6000, 14566/16584/99538 on rentacar and 6294/9719/43173 on misc04inf.
Each cone is one connected component; cap6000 matches its full LP and the other
two keep the dominant full component. Ordinary admission stays unchanged.

The three cold full/cone probes agree on cap6000's checked native lower bound
-3598896 with 1051576 root work units. Both large sources decline checked bounds
in all three probes. Cone/full root work is 1071026/1072896 on rentacar and
1126811/1221906 on misc04inf. Checked-bound thread allocations span 33.7–34.1 MB
for the cap6000 cone, 50.1–50.2 MB for rentacar and 100.5 MB for misc04inf;
these are JFR diagnostic phase allocations on Mac/aarch64, Java 25.0.4.1 with
a 6 GiB maximum heap, excluding physical RSS and any end-to-end speed claim.
Restored support establishes projection fidelity; dominant-component size and
missing large-source deductions still withhold an automatic admission bypass.

[Numerical checkpoint control 7991](https://lab.eignex.com/jobs/7991) matches
checkpointed and uninterrupted final headings, work and numerical outcomes on all
three frozen sources. Cap6000 completes its floating solve in 81.86M work units,
349 pivots and seven refactorizations; repeated stops spend 150.11M, aggregating
717 pivots and eighteen refactorizations while retaining a weaker checked bound.
The two large controls reach the same uninterrupted headings with two/five
refactorizations, versus eight/eleven for repeated stops. Their numerical progress
still supplies no checked bound, and repeated pivot totals include restarted work.

[Integrated root consultations 8002](https://lab.eignex.com/jobs/8002), at
`7308e70b4b`, retain the same hashes and existing adaptive/cumulative governors.
Cap6000 supplies a checked native lower bound of -2451537 after seven consultations,
82.49M node work units and seven refactorizations; the stopped control spends
144.17M and seventeen refactorizations for -2555534. Node overhead is separate.
The large roots retain one cursor with three/six refactorizations versus eight/ten,
but still supply no checked bound, fixing or prune. Their thread allocations fall
from 670/314 MB to 587/290 MB while diagnostic query times rise from 226/108 ms to
255/157 ms; fewer rebuilds do not establish an end-to-end speedup.

That capture continues cap6000's completed root until governor demotion, producing
64 consultations and 1.84 GB of thread allocation. The comparison's terminal rule
stops at the completed solve with its checked bound. These are Mac/aarch64,
Java 25.0.4.1, 6 GiB-heap JFR diagnostics, with no source witness/proof or physical
RSS claim. The checkpoint policy remains internal; ordinary admission and the
frozen 144-source baseline denominator stay unchanged.

### Sparse source root capture

`bench.profile.MpsSparseRoot` takes the same hash-pinned `batch64` requests as
`MpsLpAdmission`. It uses the numerical source pipeline, coordinate-side extraction
and sparse CSC preparation, then advances the existing scoped root owner at seed 3.
Preparation has 2M work units and 64 MiB modeled allocation; floating work retains
the 150M cumulative limit, 1024 pivots and a ten-second deadline. Consultations
advance at 20M work units and stop at the completed numerical operation. Dual-only
certification has its own 2M/64 MiB allowance, with no exact-primal recovery.

Each source retains original bytes and build identity. Preparation and guidance
reports separate work, measured thread allocation, active time, paused versus
completed floating outcomes, heuristic coordinates, native checked bounds and
certificate declines. Completed solves expose factor fill and density. These
captures qualify the internal source-root mechanism; they do not establish source
witnesses, configured-tolerance verdicts, physical RSS or end-to-end speedups.
Ordinary finite and source admission remain independent of this diagnostic policy.

### CLI profile tables

A lab `profileCli` case sets `KLAUSE_LAB_PROFILE_DIR`; after the subprocess timing
ends, `solve-one` summarizes the CLI's `cli.jfr` there into overlapping tables: CPU
samples and estimated allocation weight by phase (source preparation, theory
installation, session opening, root initialization, propagation, model checks,
search, presolve, LS seeding/moves and ALNS), self-samples, the first captured
operation and model-facing callers, polynomial checkpoint callers and entry-context
markers. They are sampled views of visible stacks, not wall-time attribution or
causal explanations, and their totals must not be added together. Summarization
does not change the CLI recording configuration.

### Table profiling

`TableReductionProfile` measures current-thread allocated bytes around the production
`Table.structuralReduce` hook after warmup. Run it as a lab profile job; its
`table-reduction.csv` separates unchanged and half-pruned ground tables at arities
4 and 64, with 64/4096 rows and a 4097-row cap control. Input construction and
result inspection are outside allocation accounting. These hook measurements do
not establish whole-solve performance.

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

`./gradlew :klause-bench:intDomainMicrobench` runs host-sensitive IntDomain
representation timing probes locally.

## Validation

`./gradlew :klause-bench:test` checks the harness.
`:klause:basisTrace --args="verify"` and `--args="replay"` are opt-in fixture
checks; numerical declines and missing inputs keep their normal semantics.
Benchmarks, captures and timing probes do not run as part of ordinary repository checks.

`source-ownership` contains self-authored original MiniZinc mixed integer/real
product controls, including an unrelated finite distinctness constraint and an
excluded integer value. Run CP/LS arms with default and exact modes; Klause-lab checks
the recorded witnesses against the original MiniZinc source.

## Design evidence

### XCSP3 declared intension extrema

[Lab 7973](https://lab.eignex.com/jobs/7973) compares `370fd2fd30` with `51a86c6f10`
on Fischer and BlockedQueens: mixed/P1, 10 seconds, seeds 1/3, three repeats,
debug enabled, no JFR, Mac/aarch64. All 24 cases remain UNKNOWN, without a witness
claim to check. Source hashes, runtime, options and non-solver dependencies match.

Fischer pre-bake integers fall from 41351 to 23584. Preparation takes
1267–1353 ms versus 836–875 ms; CP node counts rise from 6825–18773 to
13981–24993, and peak depth from 238–820 to 1024–1061. BlockedQueens retains
its source shape and outcome. These diagnostics support declared output retention,
reduced preparation cost and deeper search; they establish neither source recovery
nor a universal solve speedup.

### XCSP3 count channel and output reuse

[Lab 7972](https://lab.eignex.com/jobs/7972) compares `085ffb24a1` with `370fd2fd30`:
mixed/P1, 15 seconds, seeds 1/3, three repeats, debug enabled, no JFR, Mac/aarch64.
All 24 feasible witnesses pass original-source checking. Source hashes, runtime,
options and non-solver dependencies match; repeats are not independent seed samples.

ItemsetMining integers fall from 36252 to 2272, Booleans from 34785 to 1283 and
factors from 70919 to 3899. Preparation takes 1727–1950 ms versus 492–535 ms.
Objective pairs have three wins and three losses. ProgressiveParty ties at seven
in all six pairs. BlockedQueens retains its source shape and remains UNKNOWN;
its preparation takes 143–151 ms versus 163–179 ms. Retain shared indicators and
functional count outputs for their representation and preparation benefits, with
mixed search quality and no universal solve-speedup claim.

### XCSP3 constant-distance disequalities

[Lab 7970](https://lab.eignex.com/jobs/7970) compares `2a9718f55f` with `c99c067c23`
on BlockedQueens and Fischer: mixed/P1, 10 seconds, seeds 1/3, three repeats,
debug enabled, no JFR, Mac/aarch64. All 24 cases remain UNKNOWN, with no witness
claim to check. Source hashes, runtime, options and non-solver dependencies match.

BlockedQueens integers fall from 3136 to 56 and factors from 7355 to 5815.
Preparation takes 344–387 ms versus 160–173 ms, and CP node counts rise from
385–737 to 5184–6053. Peak depth is mixed: 19–24 versus 21–25. Fischer retains
its source shape. These diagnostics support reduced preparation cost and more
search progress, without establishing source recovery or an end-to-end speedup.

### XCSP3 native scalar distinctness

[Lab 7951](https://lab.eignex.com/jobs/7951) compares `5a8c2b0c13` with native
scalar `AllDifferent` retention at `58e51bbab4`: mixed/P1, 10 seconds, seeds 1/3,
three repeats, debug enabled, no JFR, Mac/aarch64. Source hashes, runtime,
options and non-solver dependencies match. All 36 feasible witnesses pass the
original XCSP3 checker; both arms leave BlockedQueens unresolved.

CVRP source Booleans fall from 9315 to zero and factors from 19289 to 795;
preparation takes 718–902 ms versus 170–210 ms. CVRP objective pairs have four
wins and two losses; TankAllocation has three wins and three losses, and
ProgressiveParty ties at seven in all six pairs. BlockedQueens retains its source
shape. These diagnostics support native retention and reduced preparation cost,
with mixed search quality; they establish no universal solve speedup.

These summaries record current design decisions. The contracts live in the linked
[docs](../docs/README.md) chapters; measurements behind them were klause-lab
experiments and stay outside the repository.

### Table and MDD historical domains

Retain shared sparse/dense ground support postings for historical reasons.
[8029](https://lab.eignex.com/jobs/8029) compares `3e8ac3841` with `b7547b3f50` on
Mac/aarch64: one conflictDriven CP arm, LP off, 2000 nodes, 20 seconds, seed 3,
three repeats and ordinary resource records without JFR. Source hashes, runtime,
options and non-solver dependencies match; all twelve witnesses pass independent
original-source checks.

WordSquare remains unknown but returns in 20.17–20.45 s with 59–61 failures;
the scan completes twice in 55.29–57.95 s with three failures and is killed once
at 60 s. Peak RSS rises from 429344–442176 KiB in the completed controls to
1307344–1347968 KiB on the longer reached conflict/learning trajectory. The index
bounds its own primitive buffers, not whole-search retained memory. Eternity's
2000-node median time changes from 3.296 to 2.992 s with different work and reasons.
Table Nonogram drops from 613 to 231 nodes; Regular keeps identical work.
WordDesign remains unknown at 39–41 nodes, with elapsed ranges of 21.73–21.76 s
for the scan and 21.77–23.44 s for the index arm. These are mechanism and trajectory
observations, not a uniform speedup or a source-recovery claim.

Retain full-domain MDD reachability and historical hole premises. The initial
[comparison 7954](https://lab.eignex.com/jobs/7954), against `5a8c2b0c1`, leaves
all 18 WordDesign2, Eternity and BlockedQueens runs unknown. WordDesign2 reaches
50 nodes and depth 17 in both arms, with additional watcher work. The hole,
snapshot and undo regressions establish the stronger contract; these candidate
measurements establish no solve gain.

Retain ground table reasons tied to the actual bound or value deduction and its
trail position. [Comparison 7959](https://lab.eignex.com/jobs/7959) rejects a
per-column/value membership cache: Eternity's work matches, while median process
time changes from 4.220 s to 4.432 s. The earlier
[profile 7946](https://lab.eignex.com/jobs/7946) attributes cost to support-loss
reconstruction; its execution-sample share is not a predicted speedup.

[Comparison 7966](https://lab.eignex.com/jobs/7966) pairs `81ea84064` with
`4e876bfb2` on Mac/aarch64, CP/LP-off, one conflict-driven arm, seed 3, three
repeats, 2000 nodes and a 20-second deadline. External time measures ordinary
CLI runs with debug statistics and no JFR. Eternity's median process time falls
from 4.078 s to 3.233 s; failures change from 6051 to 5701, propagation work from
68236618 to 56687826, and peak depth from 25 to 28. All remain unknown. Search
paths differ, so this does not isolate explanation time. Recorded peak RSS ranges
are 235872–273120 KiB and 236784–237920 KiB respectively.

The table Nonogram control takes 613 rather than 375 nodes, with median process
time 1.258 s rather than 1.087 s. All twelve table/Regular witnesses pass original
source checking. Regular and BlockedQueens work match; deadline-limited MDD runs
reach 39–41 nodes and remain unknown. These controls support the focused reason
change with a search tradeoff, not a general speedup. The measured Table source
is unchanged by its rebase onto the MDD contract. See
[explanation lifetime](../docs/09-learning.md#table-explanations).

Retain the per-variable search-time hole index. [Comparison 7993](https://lab.eignex.com/jobs/7993)
pairs `443b3c12a` with `4a3642171` on Linux/amd64, CP/LP-off, one conflict-driven
arm, seed 3, three repeats, 2000 nodes and a 20-second deadline. Ordinary CLI runs
use debug statistics, external time and no JFR. Two completed WordSquare scan
runs take 57.050 and 59.950 s; the third is killed by the 60-second lab watchdog
without search statistics. All three indexed runs finish in 32.890–34.296 s.
The two completed pairs match at two nodes, one failure and 1642588 propagation
work; all six runs remain unknown. Completed scan peak RSS is 441072–451604 KiB,
versus 481840–486352 KiB with the index. This is a lookup improvement with extra
memory, and cancellation still substantially overruns the requested deadline.

Eternity's median process time changes from 6.150 to 5.906 s with identical
2000-node work and unknown verdicts. Table Nonogram and Regular medians remain
near 2.26 and 1.12 s with identical work; all twelve witnesses pass original-source
checking. The comparison establishes neither a general speedup nor a solve gain.
The indexed history and undo sources are unchanged by rebase onto main.

Retain atomic interior Table pruning with per-value reasons. [Comparison 8006](https://lab.eignex.com/jobs/8006)
isolates `381710d79` against indexed parent `4a3642171` under the same settings
and source/runtime identities as 7993. All three WordSquare pairs retain two nodes,
one failure, depth two and 42 propagations. Median CLI time falls from 34.020 to
28.443 s, reported propagation time from 6.263 to 0.647 s, and propagation work
from 1642588 to 832668. Peak RSS ranges are 490772–492036 KiB and 324888–342204 KiB.
All remain unknown; the 20-second deadline still overruns by 7.9–10.9 s with
batching, so domain reconstruction does not explain the entire overrun.

Eternity retains its search counts with mixed timings (median 5.909/5.959 s).
Table Nonogram and Regular retain identical work and nearly equal medians
(2.320/2.319 s and 1.110/1.112 s); all twelve witnesses pass original-source
checking. These controls support the focused pruning change without a general
solve-speed claim. The measured Table and mutation sources survive rebase unchanged.

### Division relation lookup

Retain the compilation-owned index described in [integer division relations](../docs/02-model.md#integer-division-relations).
Matched Mac aarch64/JDK 25.0.4.1 construction profiles
[7952](https://lab.eignex.com/jobs/7952) and [7953](https://lab.eignex.com/jobs/7953)
used identical profile source at `e397788d9` and `7d6298534`, one warmup and three
measured iterations. Median parsing/lowering times for 1,024/2,048/4,096/8,192
repeated modular expressions were 7.14/13.58/42.83/154.43 ms with factor scanning
and 6.43/6.85/9.61/19.34 ms with the index. Coprime-24_c25 construction fell from
53.75 s to 0.189 s, retaining 28,199 integer coordinates and 172,524 factors.
Source hashes, factor kinds and runtime identities matched across the profiles;
the actual profile JVM exposed ten processors and executed no solver.

The unprofiled debug-mode [7955 controls](https://lab.eignex.com/jobs/7955) reported
Coprime load times of 37.3 s and 0.67 s; both runs stayed unknown at a ten-second
solve limit. The signed division fixtures retained their reported outcomes.
The maintained checker accepted both linear SMT witnesses, declined the four
SMT witnesses containing division and could not locate the vendored XCSP3 and
MiniZinc sources. Native refutation and optimum reports therefore receive no
independent proof credit from this job. Construction gains establish neither
solver recovery nor a corpus-wide solve speedup.

The four spot-host debug-mode [7958 corpus controls](https://lab.eignex.com/jobs/7958) retained
Coprime-08_c25 CP objective 31 and local-search objective 53 in both revisions at
seed 3 and five-second limits. The maintained [7962 recheck](https://lab.eignex.com/jobs/7962)
accepted all four original-source instantiations. These witness checks establish
source feasibility; they do not certify the CP optimum reports.

### Division propagation supports

[Search propagation](../docs/08-search.md#selected-propagation-projections) owns the
state-local bounded tuple cache, restored-operand coverage checks and live dividend
buckets. This is separate from the compilation relation index.
The state-wide retention allowance keeps large families of small relations from
materializing every tuple product; unretained relations use complete full scanning
without constructing bucket metadata.

Ordinary [7986](https://lab.eignex.com/jobs/7986) compares main `3b34027` with
retained full scanning `a75c892`: nine sources, CP/LP off/P1, seed 3, 20 seconds,
three repeats, debug off, on Mac/aarch64. All 54 outcomes agree; median original
ChainReaction nodes rise 16,189→20,370. Separate matched JFR
[7981](https://lab.eignex.com/jobs/7981) estimates search allocation weight of
27.83→13.95 GB in its first pair. Weights are statistical, not exact allocation.

The AWS/Linux/amd64 index isolation [7995](https://lab.eignex.com/jobs/7995) has
about 0.5% more median nodes but higher allocation weight, 8.43→9.10 GB;
its lower filtering sample share establishes no whole-solver speedup. Ordinary
[8009](https://lab.eignex.com/jobs/8009) compares main, bounded full scanning and
bounded indexing across ten sources with the same settings. All 90 outcomes agree.
The single-host AWS ChainReaction-20 cohort reaches median 10,574/12,408/12,922
nodes; Mac Coprime-08 retains objective 31 and 167 nodes, with propagation medians
632/505/475 ms. Wide-root Mac indexing trades away nodes against full scanning.
All 18 Coprime and linear SMT witnesses pass independent checking. Eighteen
division-bearing SMT witnesses are declined and eighteen vendored witnesses lack
checker source resolution; native proof reports are not independently certified.

Retain bucket metadata only for admitted caches. Mac
[8015](https://lab.eignex.com/jobs/8015) compares `47726fa` with `84274bb` at the
same settings: all 24 outcomes agree and all six Coprime witnesses pass checking.
The 10,000-pair-root ChainReaction-70 median rises 5,556→6,377 nodes; the original
changes 21,275→21,102 and Coprime propagation 476→477 ms at matching work.
Large Coprime-40 remains unknown in preparation, supplying no search-throughput
or cache-hit evidence. Sources, runtime, options and dependencies match within each
actual platform cohort. These fixed-seed, elapsed-dependent portfolio runs establish
neither identical trajectories nor a corpus-wide speed or verdict gain.

### Elective LP harvest controls

`sets/lp-harvest-controls-v1.txt` fixes the SAT and positive controls for the
automatic clausal satisfaction gate. Keep `lp-harvest` elective: on these controls an
explicit harvest left search work unchanged and cost 1.2–1.6x median full-process
time. See [presolve](../docs/07-presolve.md#pipeline).

### Presolve rerun spending

The round scheduler defers costly low-yield reruns by charged work per retained
reduction, alongside its minimum model-change threshold. It reduced charged
preparation work on the controls without a general speedup claim. See
[presolve](../docs/07-presolve.md#pipeline).

### Source preparation reuse and polling

Reuse captured open plans only for the identical model and projection binding, and
retain bounded metered probe polling with exact visit charges and transactional
retraction; see [source preparation](../docs/07-presolve.md#source-routing-and-encoder-selection)
and [probing](../docs/07-presolve.md#symmetry-and-probing).

[Lab 7712](https://lab.eignex.com/jobs/7712) compares the merged plan-reuse baseline
`9f40a0678c` with probe polling at `3008350cf1`: six original SMT sources, seed 3,
three repeats, one core and 10 s. All 36 cases finished. Every same-host pair matches
source, dependency and runtime identities, and all work ledgers reconcile. The six
cooking witnesses passed source checking; BubbleSort and startup returned native
UNSAT in all twelve runs, without independent proof checking. The eighteen tdf,
broydn and skdmxa runs remained UNKNOWN. Local and cloud timings stay separate;
changed deadline work prefixes establish neither decision recovery nor a general
solver speedup.

### Original-clause subsumption

Keep `clause-subsume` opt-in: it helped one WCNF control and made no reduction on
the negative controls. See [presolve](../docs/07-presolve.md#boolean-reductions).

### Tuple distinctness

XCSP3 tuple `allDifferent` retains one native relation with source-sized storage;
see [tuple semantics](../docs/02-model.md#distinct-integer-positions).
[Lab 7851](https://lab.eignex.com/jobs/7851), at `79809f8a7`, uses the default
portfolio, seed 1, debug statistics, 60 s, macOS/aarch64, Serial GC, one active
processor and the ordinary 3 GB heap. Its `LotteryDesign-32-6-6-2-2000_c25` source
hash matches [7241 case 970](https://lab.eignex.com/jobs/7241), which exhausted that
heap in tuple lowering. The native run loaded in 757 ms, reached search and returned
UNKNOWN with exit 0 and peak RSS 2,275,056 KiB. The seven-row `0007_c25` control
returned SAT and passed the independent XCSP3 SolutionChecker. These external-time
captures establish source admission and a checked small witness, without a large
instance verdict or a runtime speedup claim.

### Portfolio slice policy

Retain the finite schedule's base 5000, maximum 150000 and growth 1.5; alternatives
helped specific workloads without a general replacement. See
[portfolio scheduling](../docs/12-portfolio.md#slice-accounting-and-coverage).

### Linear recipe coverage

Propagator-only symmetry preserves the existing integer-linear LP companion.
[Current-main comparison 7836](https://lab.eignex.com/jobs/7836), at `840c3b0ad7d`,
uses 60 seconds, one processor, seeds 3/17 and three repeats. CoinsGrid's CP pool
proves 200 in six runs; mixed proves none and lacks the LP recipe. Wordpress
returns checked 1442 without proof in all twelve runs, so its historical CP proof
advantage does not reproduce. Mixed gives its default LP worker 14.0–17.4 seconds
versus CP's 4.6–4.8 seconds; one CP tree worker reports `LpAssemblyCancelled`.

The CoinsGrid cohort in [causal comparison 7855](https://lab.eignex.com/jobs/7855)
uses 20 seconds with the same seeds and repeats. Admission at `a906cdd52` recovers
six proofs at 200 versus none for baseline or the companion with the same position
and seed but LP disabled. The LP worker uses one segment, 228 work units and 77–82 ms per run;
process elapsed is 1.138–1.303 seconds. All eighteen target witnesses pass source
checking. An extra worker alone does not recover these proofs.

All 39 final witnesses in comparison 7836 pass original-source checking. Mixed finds WordGolf witnesses
in 3/6 runs versus CP's 0/6 and has better LowAutocorrelation objectives in all six
pairs. These controls support neither replacing mixed search with CP nor a general
timing claim. The comparison uses matched source/build/runtime identities on
Mac/aarch64 with lab debug statistics enabled. Source-witness checks do not
independently certify native optimality.

### Continuous leaf lifetime

[Residual replay 7909](https://lab.eignex.com/jobs/7909) preserves exact points,
terminations and non-timing numerical metrics through 173 advances of a captured
cap64 query. The uninterrupted and sliced paths use the same invocation limits
and native work prices. The paired cumulative-refactor control also completes
through 72 advances. These captures omit original factors and establish numerical
parity, with full-source checks supplied separately.

[Qualification 7929](https://lab.eignex.com/jobs/7929) and
[repetitions 7930](https://lab.eignex.com/jobs/7930) compare the retained leaf cursor
with main on thirteen frozen MPS identities, with three repetitions of dsbmip,
misc06, rentacar and flugpl. All 168 runs use seed 3, one processor, debug statistics
and ten seconds on Mac/aarch64; default/CP and exact/declared modes remain separate.
Shared immutable condition snapshots remove rentacar's repeated attribution-map
copies before numerical solving. Median maximum segment times in default declared
mode are:

| Source | Main (ms) | Retained leaf (ms) |
|---|---:|---:|
| dsbmip | 9451 | 562 |
| misc06 | 9726 | 585 |
| rentacar | 9097 | 914 |

Exact and CP modes show the same segment reduction. Active workers increase from
one to seven or eight in default mode and six in CP. The three hard sources remain
undecided. All 24 flugpl runs return 1201500 with a native optimality result and
identical pivot/work totals within each mode. Default first-witness medians increase
from 532 to 541 ms declared and 547 to 558 ms exact.

The thirteen-identity screen has six objective improvements, four regressions and
eighteen ties among feasible pairs, with no paired witness loss; withheld identities
have one sample per mode. The independent checker accepts 68 final witnesses across
both jobs. Four exact cap64 points have nonterminating rational coordinates the
checker cannot read; their internal exact source checks remain separate from that
unsupported external check. Witness checking does not establish native optimality.
Encoding, assembly, factorization and certification remain atomic and can overrun
a slice.

### Continuous candidate owner reuse

Continuous local-search candidates use fresh numerical owners. The
[retained-owner prototype](https://github.com/Eignex/klause/tree/codex/mps-completion-reuse-prototype-3456)
passed current-pin, conditioned-row, indicator, cancellation and cleanup controls,
but its cold-search evidence does not support enabling it unconditionally.

[Identical-sequence replays](https://lab.eignex.com/jobs/7921) preserve candidate
order, source policy and checks while alternating cold and retained owners after
warmup. Gesa2's three-query declared sequence falls from 687 to 293 ms and 728 to
367 MB of Java thread allocations, with two warm hits. That trajectory starts from
a saved incumbent. [Cold cap64 sequences](https://lab.eignex.com/jobs/7923) have two
queries and one warm hit: 392 to 245 ms declared and 395 to 231 ms exact. A one-query
seeded blend2 sequence instead grows from 10 to 25 ms declared. These JFR profile
costs exclude source parsing equally and do not establish cold-search quality.

[Cold-start comparison 7931](https://lab.eignex.com/jobs/7931) contains 144 runs
over six frozen sources, seed 3, three repeats, one processor, debug statistics and
ten seconds. Default/LS and exact/declared modes remain separate. Both arms charge
component work at the existing instruction price. There is no paired witness
loss; objectives have nine wins, seven losses and twenty ties, with 36 pairs
undecided in both arms. The external checker accepts 66 points; six exact cap64
points remain unsupported because their rational coordinates have no exact decimal.

Cap64 improves in all six default pairs. In LS declared mode its objective instead
changes from 1129864.4 to 1146638.65 in all three repeats, and median completed
candidate checks fall from 15 to 13. LS exact mode ties its objective while completed
checks fall from 16 to 13 and first-witness median grows from 1034 to 1123 ms.
Blend2 records no LS completion calls; cold LS gesa2 has none either. Earlier
[cold control 5162](https://lab.eignex.com/jobs/5162) keeps qnet1/blend2 objectives
tied and records one win, four losses and one tie on default gesa2-o; exact
qnet1/gesa remain unknown. Earlier timed gains were hardware-confounded;
[equal-work controls](https://lab.eignex.com/jobs/5140) instead regress by 9417.3.
The positive seeded controls from [completion-aware scoring](https://github.com/Eignex/klause/issues/2997)
remain separate from cold-search quality.

[Matched CLI stage profiles](https://lab.eignex.com/jobs/7936) put 57.3% of cold
and 68.2% of retained execution samples in move selection, dominated by exact-linear
scoring; retained thread snapshots at four and six seconds show the same stage.
Profile instrumentation excludes a throughput claim. The `lsMoves` counter includes
completion work, so its rate alone cannot measure ordinary move throughput. Warm
numerical savings remain useful evidence, while production keeps per-candidate
numerical ownership.

### Mixed exact-linear scoring

[Baseline 7978](https://lab.eignex.com/jobs/7978) and
[incremental 7979](https://lab.eignex.com/jobs/7979) run identical candidate sequences
on Mac/aarch64 with matching runtime/dependency bytes. All score, selected-index
and final-degree hashes agree. Median scoring reductions at widths 8/64/512/4096
are 1.35/3.84/20.98/112.48 times; both arms allocate zero measured scoring bytes.
From the primitive-call count and source loops, the baseline visits twice the row
width per delta; the fixed tree reads one coefficient and reduces a logarithmic
path. These are structural counts, not instrumented visit counters. Tree storage
adds state memory proportional to row width. Exactly representable inputs isolate
mechanism cost; balanced and sequential floating sums can otherwise round
differently. These profiles establish no whole-solver speed or quality gain.

[Cold comparison 7987](https://lab.eignex.com/jobs/7987) compares `1fcc42f00c`
with `aac5628111` in 192 runs over eight frozen source identities, with mixed/LS
and exact/declared modes separate, seed 3, three repeats, ten seconds and one
processor. Debug is off and physical CLI resources are retained. All pairs match
original hashes, actual Mac/aarch64 runtime/options and host. There is no paired
feasibility loss; 66 witnesses pass independent source checking. Six exact mixed
cap64 points remain unsupported because the checker reads decimals only.

Checked objective pairs have nine wins, three losses and 21 ties; the remaining
three native improvements are those unsupported cap64 pairs. Sixty pairs remain
undecided in both arms. Declared LS cap64 completion attempts rise from 55 to 68,
with accepted attempts from 47 to 54 and identical final objectives. Exact LS
cap64 instead retains 57 attempts while accepted attempts fall from 48 to 45.
These counts do not identify distinct points, and LS work counters include
completion work. Deadline-dependent mixed objectives and these one-seed repeats
establish no uniform quality or whole-CLI speed improvement. The completed
144-identity reference baseline remains the coverage denominator; native proof
claims have no independent proof certificate here.

### Numerical kernel decisions

[Owned-scatter controls 7989](https://lab.eignex.com/jobs/7989) and
[7990](https://lab.eignex.com/jobs/7990) compare complete cold/retained numerical
lifecycles on the same captured dsbmip and cap64 residuals at 120 million work units.
With debug statistics disabled on Mac/aarch64, three measured JFR rounds reduce
dsbmip median cold/retained lifecycle from 131.1/120.1 to 119.7/110.4 ms and
cap64 from 140.9/139.8 to 130.5/128.7 ms. Pivot counts, charged work and exact
result packages match. Separate observed rounds retain equal factor-slice samples
and source-matrix residuals; the broader four-residual controls
[7976](https://lab.eignex.com/jobs/7976), [7977](https://lab.eignex.com/jobs/7977),
[7984](https://lab.eignex.com/jobs/7984) and [7985](https://lab.eignex.com/jobs/7985)
also preserve them, with sampled relative residual at most 8.2e-14. These captures
measure the fused basis traversal, including updates and cleanup; they establish
no ordinary whole-source speedup or source proof.

[Session replay 7872](https://lab.eignex.com/jobs/7872) compares cumulative-work
rebuilds on identical MPS residuals. Disabling that trigger reduces cap64's median
two-cold-query lifecycle from 1.623 s to 0.793 s, retaining its native feasible
package while charging 2.5 times the LP work. The nonempty solve allowance uses
the backend-independent shape price. These Mac/aarch64 JFR timings establish no
ordinary source gain. With source-matched unbounded work,
[7875](https://lab.eignex.com/jobs/7875) reaches 3150 dsbmip pivots per two seconds
against about 1696; both remain indeterminate.

[Control 7877](https://lab.eignex.com/jobs/7877) certifies all ones at objective 40
on a near-dependent 40-row model under both policies. Forced dense construction
at 2.5–5% basis density increases median cold lifecycle from 1.856 ms to 2.499 ms;
the dense admission envelope stays at order six. The same attribution records
5945 dense-hint dsbmip BTRAN calls with total RHS support two, motivating the
empty-RHS path's zero-visit solve. Larger adaptive transpose solves retain their
reach and density guards.

Retained exact feasibility reuses a fully imported tableau across compatible bound
assertions and retractions under cumulative resource limits. In the Mac/aarch64
overflow comparison [7786](https://lab.eignex.com/jobs/7786), continuation builds
fell from 727–757 to 105 and complete linear checks rose from zero to 1819–1830;
all original verdicts remained UNKNOWN. These are mechanism and search-progress
observations, not an end-to-end speedup or independent refutation.

[Capture 7793](https://lab.eignex.com/jobs/7793) retains 149 active rows and 72
structural columns, with no free logical definitions to remove. Shared logical and
certificate storage represents them in 33,078 cells instead of 55,279. Partial
Boolean states retain a 128-row continuation allowance: unrestricted admission
stopped at 43 open Booleans in all three [7820](https://lab.eignex.com/jobs/7820)
repeats. In [7829](https://lab.eignex.com/jobs/7829), reserving larger capacity for
Boolean-complete checks produced 33 exact, explained LP conflicts in each repeat,
with modeled allocation about 973M to 833M and similar work. The SMT verdict stays
UNKNOWN; these are completion and resource observations.

Retain the bounded dense-LU selection with windowed updates (direct benefit measured
at order six; larger bases favored sparse construction), the fraction factory paths
that skip redundant normalization when coprimality is established, and the current
cancellation polling frequency. See [LP architecture](../docs/17-linear-programming.md)
and [exact arithmetic](../docs/18-exact-arithmetic.md#numerical-dependencies).

Validated CSC column pricing was compared with pinned current and dependency-only
controls in [lab 7615](https://lab.eignex.com/experiments/7615): 19 sources, three
repetitions, seed 1, one core and 10 s, plus reference arms. All 171 Klause cases
executed; feasibility, objective and proof claims matched across the three arms.
Recorded work counters matched on 16 sources; time-capped LP work varied on three.
Solchecker accepted 81 witnesses. Another 63 checks were unsupported, nine were
undecided and 18 infeasibility claims had no witness check. CLI elapsed time includes
startup and preparation; full case intervals and separate build/setup logs retain
the remaining setup costs. The mixed timings establish no solver or SIMD speedup.

### LP query allowance recovery

[Requalification 8026](https://lab.eignex.com/jobs/8026) reproduces the six named
[MPS losses](https://github.com/Eignex/klause/issues/3518) on `3e8ac3841` against
`0cdb35141`. [8027](https://lab.eignex.com/jobs/8027) isolates four feasibility
losses to the observed-degradation refactor switch. The first post-baseline
snapshot in [8039](https://lab.eignex.com/jobs/8039) also loses misc05inf's native
result; both dcmulti sources vary across those
intermediate revisions. Those controls support no single offending commit for
dcmulti. The [empty-transform control 8032](https://lab.eignex.com/jobs/8032)
leaves all seven sources undecided.

[8041](https://lab.eignex.com/jobs/8041) compares integrated `3e8ac3841` with
bounded query feedback at `b97239a4a2`: ten original identities, Mac/aarch64,
P1, seed 3, three repeats and 60 seconds. Original hashes, runtime, options and
non-solver dependencies match; all 21 final witnesses pass solchecker. Native
proofs are not independently proof-checked, and repeats are not independent seeds.
Both dcmulti sources and egout change from zero to three feasible runs; misc05inf
changes from UNKNOWN to native infeasibility in all three. gr4x6 retains 202.35
and gains three native optimality reports. f2gap201600 retains 76453 and mod008inf
retains its three native refutations in each arm.

Node LP conflicts increase from zero to 9–10 on each dcmulti source, 42 on egout
and 71 on misc05inf. Both Gesa2 originals remain UNKNOWN but gain four explained
LP conflicts per repeat; gesa3_o remains UNKNOWN without an LP conflict. These
are scoped deductions, not completed refutations of the remaining sources.
Median node LP time increases from 0.329 to 1.218 s on miplib2017/dcmulti, 0.243
to 0.452 s on egout, 2.262 to 9.742 s on gesa2-o and 0.423 to 5.386 s on gesa3_o.
The last control spends more LP time without a recovered verdict or conflict.
These LP clocks exclude root work and establish no general throughput gain;
allocation and peak memory are unmeasured. Retain the refactor policy and existing
allowances while funding progressing queries before assessing their utility.

### Native arithmetic cancellation

Native polynomial polls inspect shared work, worker quotas and representation stops
under one ledger lock, then call containing predicates outside it; see
[resources](../docs/06-sessions.md#mechanism-and-compatibility).
[Lab 7807](https://lab.eignex.com/jobs/7807) compares `8ac1afcfcf` with `4ef0caba09`
on four retained polynomial sources, three alternating pairs, complete-only CP,
seed 3, one core, debug enabled and no JFR, on Mac/aarch64. At 5,000,000 native
units, sorting and kissing median full-process times fell from 2.105 to 2.037 s and
2.074 to 2.027 s. Source/runtime identities, recorded work and outcomes matched in
all twelve pairs. The two refutation controls have no independent proof check.

[Lab 7828](https://lab.eignex.com/jobs/7828) repeats this fixed-work question with
debug disabled at `840c3b0ad7` versus `77bfce26f0` on Linux/amd64, one Spot instance.
Sorting and kissing median full-process times fell from 4.963 to 4.390 s and
4.916 to 4.246 s, with matched work and outcomes in all twelve pairs. These platform
and runtime scopes are separate measurements.

Separate [baseline](https://lab.eignex.com/jobs/7799) and
[candidate](https://lab.eignex.com/jobs/7800) pipeline diagnostics retain first
expired-poll and return latency across linear and polynomial sources. Their small
instrumented samples establish neither default CLI latency nor a general speedup.
Polling frequency, parent predicate order and charge boundaries remain unchanged.

### Polynomial numeric conflicts

At `86bb866`, [raw diagnostics](https://lab.eignex.com/jobs/7906) retain two exact
three-premise conflicts on late UNSAT controls with one, two or four unrelated
Boolean pairs. Exclusion reuses grow 4/16/160 while issued regions fall from
4/8/32 to zero. [Prepared diagnostics](https://lab.eignex.com/jobs/7907) retain
five-premise conflicts including graph equations; no prefix reuse is observed
after source preparation.

Retaining feasible sign fixpoints removes repeated reconstruction during numeric
extensions. Raw LassoRanker reaches its component witness with one build and 365
extensions, spending 55,368,234 native units. Rebuilding each check stops at
176,599,649 units without a region; the baseline without partial checks reaches
the same 29 polynomials/1408 cells in 43,802,244 units. Prepared LassoRanker also
reaches a region again. Component results establish no default source recovery,
and arithmetic overhead remains relative to the baseline.

[Run 7914](https://lab.eignex.com/jobs/7914) compares all three policies across
15 sources and three repeats on Mac/aarch64, P1, seed 3, debug enabled and 10 seconds.
All 135 native outcomes match; all 54 SAT witnesses pass Dolmen. The retained policy
saves about 5–17% native work on UNSAT controls and adds about 1% on SAT controls.
Sorting completes more CAD work than the rebuilding policy; all three originals
remain UNKNOWN. Counters accumulate actual attempts, including discarded snapshots.

[Regression 7919](https://lab.eignex.com/jobs/7919) preserves all 68 paired outcomes
across core, heldout, original, mixed and Boolean sources. Each arm has 37 independently
accepted SAT models, one unchecked DIMACS SAT and one power-syntax SAT the checker
cannot parse. Native UNSAT has no independent certificate. Heldout Boolean UNSAT
work falls 73,705→71,744 units; corresponding SAT work rises 76,978→77,278. Boolean
equivalence refutation rises 76,620→77,539. These charged-work observations and
single regression samples establish no uniform runtime speedup.

### Polynomial Boolean bookkeeping

The first-region control in `PolynomialBooleanRegionsTest` excludes normalization
and includes enumerator setup. At test-only `e05e934cd`,
[JVM CI](https://github.com/Eignex/klause/actions/runs/40071689348) charges
983/2779/8867/31027 units for 8/16/32/64 independent Boolean two-leaf disjunctions.
The incremental witness and leaf-cursor implementation is guarded by a bound of
twice the preceding family work plus 32 units at each doubling, and fewer than
10,000 units at 64 pairs. Small ordered-region controls cover shared compounds,
retraction, numeric alternatives, negative premises, support ties, leaf order and
interruption. These controls isolate Boolean bookkeeping; whole-source quality and hard-source
throughput remain unmeasured.

### State ownership

Retain concrete buffer owners, separate indexed vector roles and one cursor/cleanup
owner per run; matched comparisons showed unchanged results and work with at most
about 0.5% allocation change. See [LP architecture](../docs/17-linear-programming.md)
and [local search](../docs/10-local-search.md).

### Local-search definition propagation

Retain state-owned evaluation caches and pending-node intersections with the full
affected cone; [local search](../docs/10-local-search.md#definitions-and-repairs)
owns their invalidation and reconciliation contracts.
[Lab 7646](https://lab.eignex.com/jobs/7646), at node limit 5000 with one fixed
CBLS arm, seed 1 and three repeats, reduced median complete-case elapsed time from
9.59 to 4.10 s on steelmillslab `bench_2_19` and from 18.73 to 6.56 s on
`bench_16_10`. Team-assignment and gbac retained identical source-checked witnesses;
elapsed-driven restart differences prevent a trajectory-equivalence claim.

[Lab 7667](https://lab.eignex.com/jobs/7667) compares sparse/packed traversal at
`1a348f4706` with main `fc34aad83d`: 17 inputs, LS and mixed engines, three seeds,
three repeats and 10 s. All 612 cases completed; all 276 feasible witnesses passed
original-source checking, with no paired feasibility loss. Both mixed arms found
90/90 steelmillslab witnesses; the historical feasibility losses also do not
reproduce on this main control. Per-instance median paired move-rate ratios were
1.51–2.25 for LS and 2.31–3.98 for mixed steelmillslab runs. First-feasibility times
did not improve consistently; objectives had five wins, six losses and 79 ties
([quality follow-up](https://github.com/Eignex/klause/issues/3406)). The 72 IHTC
pre-solver compile timeouts from [7647](https://lab.eignex.com/jobs/7647) remain
outside this LS-only rerun.

All 191 steelmillslab incumbent publications in 7667 came from the annotation CP
worker. Every pair has the same first objective; the 79 pairs that stop at that
first publication have identical witnesses. The eleven unequal pairs differ by a
later CP improvement, published 9.30–10.25 s into the process. Unchanged main at
seed 1 returns 54, 54 and 51 on `bench_16_10`, and 56, 52 and 56 on `bench_15_11`.
The corresponding median annotation-CP work across all nine runs is 13775 versus
15508 and 15524 versus 15524 for main and sparse/packed traversal. Individual
losses remain measured losses, but move rates and a shared seed do not establish
equal CP work or scoring trajectories under elapsed-dependent scheduling.

[Lab 7676](https://lab.eignex.com/jobs/7676) isolates both reported inputs at seed 1,
three repeats and one core, comparing `fc34aad83d` with merged runtime
`fa9acca195`. Optional presolve is off throughout. All 36 witnesses passed source
checking. Annotation-only CP uses exactly 500 decisions and returns identical
witnesses on both revisions in every repeat: objective 48 on `bench_16_10` and 49
on `bench_15_11`. Mixed 10/20 s pairs have two wins, three losses and seven ties;
both revisions beat the originally missed objectives at 20 s. These controls
support elapsed-dependent portfolio variation without reproducing a scoring defect;
they do not establish equal optimization quality across deadlines or seeds.

Control costs vary: mapping/code-generator paired move-rate ratios span 0.84–1.19.
In the three-way fixed-arm [7660](https://lab.eignex.com/jobs/7660) guard,
`ring_mp3` CBLS median elapsed time was 919 ms on main, 922 ms with packed-only
traversal and 961 ms with sparse/packed traversal; chain medians were 4637, 4763
and 4026 ms. Work and elapsed-driven restart variation limit equivalence claims.

### Feasible board-swap coverage

Board-swap evidence separates missed improving swaps, genuine swap-local optima
and ineffective ILS perturbations. Measure
[bounded retained coverage](https://github.com/Eignex/klause/issues/3421) separately
from [effective ILS perturbations](https://github.com/Eignex/klause/issues/3422).
[Lab 7701](https://lab.eignex.com/jobs/7701), based on `b00ecc68ac`, compares sampling,
a read-only complete swap audit and best-swap rescue on both original
team-assignment sources, reduced_UD6-gbac and the seven-position photo permutation.
Each case uses one CBLS plateau worker, fixed or ILS restarts, optional presolve off,
seeds 1/3, two repeats and a 2000-unit shared allowance: all spend 2000 and report
1600 LS moves. All 96 final witnesses pass original-source checking. Final witnesses
and non-timing LS counters agree in all 32 baseline/audit pairs and 48 duplicate
groups; elapsed-dependent incumbent publications do not establish identical paths.

In one repeat per seed/policy, sampling misses improving feasible swaps at 34/65
restart boundaries on `data2_6_12` and 56/56 on `data3_4_31`. Every scan covers all
330/1395 admissible board swaps through ordinary ownership and generated-move
scoring. Fixed-restart rescue changes objectives 15920/16958 to 17920/16921 and
28750/30749 to 38676/42711; gains are not universal. Complete scans also find true
swap-local optima: checked final witnesses score 16921 and 42711 against the
source-matched optima 17946 and 44706. Original source hashes, board permutations,
rating balance and the `1000 * happiness - balance` objective are unchanged.

ILS returns identical board assignments after 13/51 small-source and 6/45
large-source sampled feasible restarts, including eleven with improving swaps.
After complete-scan descent it leaves the boards unchanged at 15/44 and 10/36
swap-local-optimum restarts. Fixed restarts have no unchanged boards at these
boundaries. Coverage, local optima and ineffective anchor perturbations are distinct
observations; these counts select no replacement restart policy.

[Lab 7710](https://lab.eignex.com/jobs/7710) removes audit instrumentation and tests
eager rescue under the unchanged default mixed/P1/presolve route: 10 s, three seeds
and three repeats. All 72 witnesses pass source checking. Small-source pairs have
two wins and seven losses, with median objective 14770 to 11866; all nine
large-source pairs lose, with median 8574 to 4500. The gbac control has six losses
and three ties, with median minimization objective 278 to 438; the photo control
ties at six throughout. Eager restart-boundary scans are therefore unsuitable for
promotion. A retained bounded cursor still needs cancellation/resume, cost and
default-route validation; fixed-work gains establish neither whole-solver recovery
nor a general speedup.

Bounded variants starting at first feasibility, extending the restart boundary or
changing only pair order remain unsupported by their default-route outcomes:
[7736](https://lab.eignex.com/jobs/7736), [7747](https://lab.eignex.com/jobs/7747),
[7754](https://lab.eignex.com/jobs/7754), [7756](https://lab.eignex.com/jobs/7756).
First-miss strict-descent variants retain source tradeoffs in the complete
consecutive-seed checks [7761](https://lab.eignex.com/jobs/7761),
[7764](https://lab.eignex.com/jobs/7764) and [7767](https://lab.eignex.com/jobs/7767).
Keeping the original sampled elected-global draw loses mean objective on both
team sources in [7769](https://lab.eignex.com/jobs/7769). The four-way comparison
[7772](https://lab.eignex.com/jobs/7772)/[7773](https://lab.eignex.com/jobs/7773)
preserves fixed-restart controls but retains all nine small-source combined losses.

Fair elected-global cycles at `54bc980fb6` retain the original resample window and
candidate/visit allowances, with ILS perturbations unchanged. Fixed-work
[7776](https://lab.eignex.com/jobs/7776) has ten team wins/six losses and all sixteen
control pairs tied at 1600 moves. All four team/policy mean changes are positive.
Default [7777](https://lab.eignex.com/jobs/7777), mixed/P1/10 s with seeds 1/2/3
three times, has three small-source wins/five losses/one tie and seven large-source
wins/two losses. Mean changes are -114.7 and +3493.4 respectively.

Default [7778](https://lab.eignex.com/jobs/7778) uses every consecutive seed from
one through ten, twice, at the same case budget. Improvements are signed in the
source optimization direction; repetitions are not independent seed samples.

| Source | Wins/losses/ties | Mean improvement | Median objective, baseline → coverage |
|---|---:|---:|---:|
| `data2_6_12` | 11/9/0 | -706.55 | 15839.5 → 15340.5 |
| `data3_4_31` | 16/4/0 | +2941.05 | 13099.5 → 16044.5 |
| `reduced_UD6-gbac` | 11/9/0 | -0.5 | 340.5 → 302.5 |
| `photo_hkj` | 0/0/20 | 0 | 6 → 6 |

All 296 witnesses across these three jobs pass original-source checking with one
runtime/options, non-solver dependency and compiler identity. Fixed/ILS median
solve seconds change from 2.760/2.789 to 2.677/2.815 on the small source and from
7.399/8.179 to 7.806/8.115 on the large source. The large-source default gain
coexists with a small-source mean loss; this is not uniform optimization recovery
or a general speedup.

### Prepared board-restart outcomes

The coordinate-changing treatment in [#3447](https://github.com/Eignex/klause/pull/3447)
is rejected: its production-base default comparison
[7827](https://lab.eignex.com/jobs/7827) loses eight of nine large-source pairs.
This is negative evidence for that operator, not for explicit decline outcomes.

[7852](https://lab.eignex.com/jobs/7852) extends the bounded restart diagnostic on
accepted coverage, comparing main `840c3b0ad7` with observational trace `6c5e97dfc1`.
Both original team sources retain fixed/ILS controls, seeds 1/3 and a 2000-unit
allowance. All sixteen witnesses pass source checks; all eight main/trace pairs
match final witness, objective and non-timing LS counters. Runtime, dependencies
and compiler identity match. Instrumented timings establish no production cost claim.

The first 24 ILS boundaries per case include 80 feasible-before boundaries. Twelve
return the preceding searched board after repair: five ineffective kicks, three
crossover no-ops and four kicks undone by greedy repair. Ordinary preparation adds
no erasure with implicit restart seeding disabled. Two changed post-repair boards
return during subsequent search; some unchanged post-repair boards later improve.
These observations support explicit post-repair decline results while preserving
the following walk. They establish neither absence of admissible moves nor a better
restart operator. Diversity, ordinary-route quality and cost remain separate measures.

Production [7885](https://lab.eignex.com/jobs/7885) compares `9f4f9b0496` with
`da9dba7685` without debug instrumentation on both team sources, gbac and photo.
All 32 witnesses pass source checks; all sixteen fixed/ILS pairs match final witness,
objective and existing non-timing counters at 1600 LS moves. ILS changed/declined
totals are 47/9 and 52/10 for the team sources, and 70/18 for photo. Fixed controls
and gbac omit unobserved outcomes. Small/large median ILS solve seconds are
3.118/3.062 and 9.002/9.025; gbac is 2.935/2.713 and photo 0.429/0.487. These
two-seed timings are descriptive and establish no general cost improvement.

Ordinary mixed/P1/10-second [7886](https://lab.eignex.com/jobs/7886) retains seeds
1/2/3 three times. All 72 witnesses pass source checks. Small/large signed mean
objective differences are +451.3/+2652.0, with wins/losses/ties 3/3/3 and 4/3/2;
gbac is +31.8 at 4/3/2 and photo ties all nine pairs. Repeats are not independent
seeds, and this observational mechanism supports no optimization-gain claim.
Team changed/declined totals are 1689/46 and 457/28; the mixed-route restart total
also includes CP work and is not an ILS denominator. Both production runs share
source hashes, runtime, options, dependencies and compiler identity, with distinct
solver and CLI JARs bound to each revision. Adoption is for explicit prepared
input-change outcomes while preserving the operator and its following walk.

### Source-scoped derivation admission

Retain typed original-source derivations separately from native engine completion;
capture stays optional and unsupported capture withholds mathematical coverage. See
[source evidence](../docs/13-evidence.md).

### Linear traversal comparison

For bounded MPS traversal diagnostics, compare `param.linear-traversal=automatic`
with `param.linear-traversal=numerical` through the same source pipeline and pinned
original bytes. The automatic finite arm can use `engine=cp`, `param.arms=1` and
`param.bt-arm=lp-default`; the numerical arm can use `engine=fixed` for a single
complete owner. Engine selection alone does not select traversal ownership.
Retain actual routing, source tolerance, objective scale, seeds, deadlines and
platform. Default and `exact=true` are separate comparisons.

`param.linear-traversal=numerical-frontier` selects the retained bound-ordered
consumer with the same source semantics. Compare it with both numerical traversal
and the automatic finite control. `numericalFrontierPending` includes the selected,
queued and unresolved alternatives; `numericalFrontierPeakPending` records their
maximum retained count. `numericalLpPreparations` and `numericalLpReuses` distinguish
source matrix preparations from compatible scoped queries. Record their checked
bound/incumbent trajectories and replay work alongside memory and wall time.

Numerical traversal's `smtCompleteSearchStage_numerical*` fields count checked nodes,
certified bounds, strict child-bound improvements, fractional/indicator splits,
closed leaves and missing guidance. LP shape maxima and
`numericalLpShapesOutsideDimensionGuard` classify attempted adapter shapes; they do
not identify the first stop condition when cancellation or another allowance also
applies. Pending leaves include unvisited siblings and
unresolved leaves; retained nodes include the complete coverage tree. These fields
are observations rather than proof authority or a retained best-bound queue.
`numericalLpInputMaxRows` measures rows before coordinate-side extraction;
`numericalLpMaxRows` measures working rows and `numericalLpExtractedBoundRows`
sums extracted rows over prepared attempts. An exhausted operation allowance can
skip preparation, so such an attempt reports its unprepared shape.
`numericalIncumbentDominatedSplits` counts proposed fractional splits whose checked
bound already dominates the invocation's checked incumbent.
`numericalIncumbentBoundClosures` counts unexpanded subtrees closed with those
scoped bounds, including nodes without a fractional proposal. Their derivations
remain in complete sibling coverage; the final bound and original-source witness
are rechecked together before an optimum can be reported.
`nativeNominalObjectiveBound` is the checked native bound in original objective
units; it does not certify the feasible set enlarged by a positive tolerance.
Independent source witness validation remains separate from solver proof claims.

`bench.profile.MpsProofFrontier` takes `suite=ID problem=NAME sha256=HEX exact=true|false`
for one hash-matched original source. It uses the same numerical source setup and
invocation, seed 3 and a ten-second solve deadline under the existing LP allowances.
Submit through the lab with `profile.runTimeoutSec: 180` for a bounded local Mac
capture. A child JVM uses one processor, a 3 GiB heap, Serial GC and JFR. This is an
instrumented owner-state capture; ordinary matched CLI experiments measure cost.

The capture retains the original bytes, native affine system and build/runtime
identity. `incumbents.jsonl` flushes accepted incumbent values and branch conditions.
`frontier.json` records remaining alternatives with their strongest retained
ancestor or local bound, its original scope and a fresh source-evidence check.
Bound-closed subtrees remain explicit. Inspection has a separate ten-second,
4096-node allowance, makes no LP query and checks tree counts against the ordinary
stage counters. Reflection fails on an internal shape mismatch. The report does
not claim an independently validated witness, proof or configured-tolerance source
verdict; retain ordinary source validation and native/source conclusions alongside it.

The snapshot also retains every checked node's eligible fractional coordinates,
actual split and exact immediate parent/child bound difference. Both bounds are
rechecked in their original source scopes. The source LP operation, preparation,
floating, continuation and refinement counters retain their distinct units;
refinement overlaps other work and must not be added to them.
Optional `history=true` enables the internal directional-gain prototype for that
capture. It requires two nonnegative finite gain observations per direction,
normalized by movement, before ranking a coordinate. Missing, unusable or all-zero
estimates retain source order. The production default uses source order.
Optional `probe=true` checks both child relaxations for every eligible root
coordinate under a separate ten-second diagnostic allowance. Each child has one
theory check and a fresh source-adapter allowance; completed entries flush to
`root-probes.jsonl`. These queries assess missing root history on identical
parent states. They do not guide the captured traversal or contribute to its
operation, frontier, witness or proof observations.

#### Bounded comparison evidence

[7932](https://lab.eignex.com/jobs/7932) compares eight frozen
[7241](https://lab.eignex.com/jobs/7241) source identities on the `eeb2518` base:
main/candidate finite workers and column-only/scoped-coverage numerical owners.
Each default/exact arm has three repeats at seed 3, P1, ten seconds, presolve off
and debug enabled on Mac/aarch64. All 192 source hashes match; all 96 reported
witnesses pass independent solchecker. Native optimality and infeasibility claims
are not independently proof-checked.

| Source | Finite main/candidate | Numerical column / scoped coverage |
| --- | --- | --- |
| gr4x6 | 229.05, unproved | 202.35 unproved / 202.35 optimal |
| p0033 | 3089 optimal | 3447 unproved / 3447 unproved |
| flugpl | 1201500 optimal | No incumbent in either arm |
| opt1217 | -8 default, -6 exact, unproved | Root unavailable in either arm |
| p0201 | 7665–7735, unproved | Root unavailable in either arm |
| enigma | 0 optimal | Root unavailable in either arm |
| enlight4, flugplinf | Infeasible | No proof in either arm |

On gr4x6, scoped coverage closes 25 subtrees: checks fall from 257 to 107,
pending leaves from five to zero and retained nodes from 261 to 107. Exact-arm
floating work falls from 23,965,152 to 10,715,823. Physical CLI wall time is
1.30–1.35 seconds for the unproved control and 0.75–0.76 seconds for the completed
candidate. These observations support checked dominance coverage under unchanged
LP allowances. They do not justify switching automatic finite ownership: wider
roots remain unsupported, while finite traversal proves p0033, flugpl, enigma and
both infeasible controls. Finite p0201 objectives vary near the wall-budget cutoff;
the comparison establishes no general finite throughput improvement.

Exact captures [7933](https://lab.eignex.com/jobs/7933) and
[7934](https://lab.eignex.com/jobs/7934) retain the stalled incumbent, remaining
conditions and remotely rechecked bounds with matching library/CLI bytes and
stage counts. The gr4x6 control stops improving at check 184 with 202.35; two of
five remaining bounds already dominate it. The p0033 candidate reaches 3447 at
check 249, but all nine remaining bounds are weaker, ranging from 1159463/460 to
68893/20. Scoped closure cannot discharge those alternatives. No probing or
bound-ordered queue policy is adopted from these observations.

Integration rerun [7942](https://lab.eignex.com/jobs/7942) repeats the 192 cases
on `db40ae3` with retained continuous-leaf and LP lifecycle changes. It preserves
the proof and feasibility outcomes, including all six gr4x6 closure proofs and
their 107 checks; all 96 reported witnesses pass independent checking. Finite
p0201 remains unproved with wall-budget-dependent values from 7665 to 7755.

Fractional-state [7974](https://lab.eignex.com/jobs/7974) retains multiple eligible
coordinates at 126 of p0033's 132 actual splits. Directional-history
[7982](https://lab.eignex.com/jobs/7982) reaches incumbent 3447 at check 201 instead
of 249, but retains nine pending alternatives at the same 256-operation allowance.
The matched exact-policy [7983 pilot](https://lab.eignex.com/jobs/7983), with seed 3,
P1, presolve off, debug enabled and three repeats on Mac/aarch64, rejects default
activation: gr4x6 grows from 107 to 163 checks and from 10,715,823 to 17,300,008
floating work units. Physical CLI wall time rises from 0.76 s to 0.91–0.96 s;
p0033 floating work also increases, from 576,285 to 618,076. All twelve reported
witnesses pass solchecker; native proofs are not independently checked.
The twelve same-parent [7992 root queries](https://lab.eignex.com/jobs/7992) keep
source order's first coordinate under the estimator, supplying no justification
for production probing. These results retain source order as the default and the
history mechanism as an internal research option; earlier incumbent progress is
separate from total search cost and proof completion.

[7997](https://lab.eignex.com/jobs/7997) completes 120 numerical controls over ten
sources with default/exact policies and three repeats on one Linux spot host.
All 24 reported witnesses pass solchecker. Gain history preserves outcomes and
reduces pending alternatives on enlight4 from seven to three and flugplinf from
fifteen to five, with slightly less floating work, while reproducing the gr4x6
cost regression. The lseu and markshare1 holdouts have unavailable roots in both
arms. A separate checked-refutation-priority
[8007 prototype](https://lab.eignex.com/jobs/8007) reaches p0033's incumbent at
check 143 but increases floating work to 647,201 and pending alternatives to
eleven; it is rejected rather than integrated. The instrumented capture proves
no whole-CLI timing or source verdict.

[7999](https://lab.eignex.com/jobs/7999) completes 24 paired exact-policy default
controls on a second Linux spot host. Source-order numerical gr4x6 retains its
107-check proof, while automatic finite p0033 retains its 3089 proof. All 24
reported witnesses pass solchecker. These controls preserve routing and outcomes;
the native proof claims remain separate from independent witness checks.
