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

`source-equalities-v1` retains real, fractional, mixed, wide-integer and
polynomial-backed equality blocks, an inconsistent real block and a guarded
alternative. The [fixture notes](smoke-corpus/source-equalities-v1/README.md) pin
source hashes and mathematical expectations. Compare `off,+dependent-rows` and
default presolve separately, retaining preparation and complete subprocess times.

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
For a `profileCli` capture, add its zero-based `case` and use
`recording=profile-<capture UUID>/cli.jfr`; the same byte-hash check applies and the
summary records the source case. Capture analysis runs in the lab.

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
`equality-candidates.json` retains unconditional non-strict integral equality rows
with tagged references, exact coefficients and bounds, factor indices and whether
one equality describes the whole factor. Capture is limited to 262144 entries and
does not authorize dropping a binary partition or a row under nonzero tolerance.

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

### Element hull admission capture

`bench.profile.ElementHullProfile` takes `suite=ID problem=NAME sha256=HEX` for a
hash-pinned XCSP3 source. It uses mixed finite preparation with seed 3, a
1.65-billion work allowance and a thirty-second containing deadline. The report
records forced Element root assembly, selectors by index, current-thread allocation
and default / aggressive complete-group selections under the existing size guards.
Selected Element-only projections report their emitted shape and assembly cost
separately from the base admission estimate. Preparation can
query numerical bounds; the capture makes no search or source verdict claim. Use
ordinary matched CLI experiments for solve costs and independent source-witness
validation.

The shared-selector capture on Wordpress-04-10-500_c24 measured 4,990 selectors
across ten indexes, 243 rows and 5,122 columns after finite preparation. Forced
assembly used 56 ms and 28.2 MB of current-thread allocation on Mac/aarch64;
automatic admission still declined the full hull. In lab job 8293, all 32 witnesses
passed independent source checking and no worker failure was reported. On
Wordpress-04-06-500_c24, both shared-hull aggressive runs proved objective 1866;
the matched controls timed out with the same incumbent. Mixed defaults proved both
seeds with or without sharing. The other three sources retained zero recorded
root bounds; their incumbent results were mixed, including one gain and one loss
on Wordpress-08-20-500_c24. These two-seed observations establish no general timing
or default-quality improvement. Complete-group admission remains separately bounded
by the aggregate LP size guard.

Complete-group capture [8313](https://lab.eignex.com/jobs/8313), on d245cbcfac and
Linux/amd64 with Java 25.0.5, admits six of the ten index groups at aggressive
emphasis: 2,994 selectors, 219 rows and 3,122 columns. Its emitted size proxy is
731,898 cells under the unchanged 1,048,576-cell cap. Ordinary admission keeps the
large groups disabled. Selected assembly records 24 ms and 13.3 MB of current-thread
allocation; forced full assembly records 115 ms and 28.7 MB. The full projection
runs first, so these instrumented timings are descriptive rather than a paired
timing comparison. The capture assembles Element hulls without a search or source
verdict claim.

Complete-group comparison [8314](https://lab.eignex.com/jobs/8314) uses four sources,
seeds 3/17, P1/30s and debug enabled on one Linux/amd64 host. All 32 source
witnesses pass independent checking, with no worker failures. Aggressive LP
incumbents on Wordpress-08-20-500_c24 improve from 3689/3643 to 3398; other
aggressive incumbents and native proof coverage stay unchanged. Recorded root
bounds remain zero. On the original 04-10 source, node LP work falls from
1.26/1.34 billion to 2.09 million units alongside existing effort demotion and
fewer LP deductions: 2162/1994 LP prunes become zero. Mixed-default witness quality
does not regress in this cohort, but the large groups remain outside its ordinary
small-hull threshold. These two-seed observations establish no general timing,
default-quality or root-bound improvement. The paired revisions are 7500a0e1ff
and 51d8bb6a49, before the pruner's unrelated-factor allocation cleanup.

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
The retained owner uses the 50-update refactor setting.

`entry=session` exercises `LinearAuthoritySession` through the same prepared source.
Optional `funding=600000000` supplies a finite caller work ledger, a 512 MiB
preparation ledger and renewed 20M slice grants; the unfunded control keeps the
fixed policy. Compare the same request and profile source on both solver revisions.
`entry=bind` replays 32 alternating original/redundantly restricted source scopes
without solving. Each transition checks every bound witness, side and depth and
records an assertion digest outside its timed bind, plus thread allocation and
owner metrics. The first transitions include preparation; later transitions isolate
retained rebinding. These instrumented streams measure mechanism cost separately
from source-checked cold CLI comparisons.
Session and binding entries use production numerical settings; `phase1`, `values`
and `start` controls apply only to the engine entry.
The session entry records accepted exact bounds and the native source lift/recheck
after its timed advance, with the lift's own elapsed time and thread allocation.

Each source retains original bytes and build identity. Preparation and guidance
reports separate work, measured thread allocation, active time, paused versus
completed floating outcomes, heuristic coordinates, native checked bounds and
certificate declines. Completed solves expose factor fill and density. These
captures qualify the internal source-root mechanism; they do not establish source
witnesses, configured-tolerance verdicts, physical RSS or end-to-end speedups.
Ordinary finite and source admission remain independent of this diagnostic policy.

Add `layout=compare` for identity and unconditional-equality layouts on the same
hash-pinned requests and resource policy. `layout=equalities` selects the latter
alone. Comparison round zero warms up; round one reverses layout order. Pairing
changes emitted rows and support, not source inequalities or their
scope; guarded and pinned templates are excluded. Reports retain emitted shape,
floating progress, factor fill, certificate work and the separate cost/outcome of
lifting the bound through the existing native source-evidence verifier. This native
source check remains separate from an independent MPS witness or proof check.
Owner close time/allocation and final owner counts are recorded separately.

`phase1=compare values=recompute` compares first-bound and long-step feasibility
ratios; `phase1=long-step values=compare` isolates incremental basic values.
Both options accept their named single policy, and the defaults are
`phase1=long-step values=incremental`. `start=compare` isolates the retained
all-logical opening from the free-singleton opening; `start=singleton` is the
default and `start=logical` retains the control. Comparisons warm up in round zero and
reverse policy order in round one. The bounded trace separates numerical phases,
scaled objective and infeasibility samples, zero steps, passed crossings, pricing,
basis-solve work and rebuild triggers. Owner preparation has its own report.
The profile fixes floating defaults at 150M work and 1024 iterations independently
of caller policy defaults. Explicit `work=N iterations=N` changes both limits
for a separately labelled funded comparison; retain an original-limit control
first. Submit these captures as Mac-only profiles with `runTimeoutSec: 180`.

[Matched capture 8051](https://lab.eignex.com/jobs/8051) freezes `c4c8489f`, the
original source hashes, Java 25.0.4.1 on Mac/aarch64, a 6 GiB heap and the policy
above. Source defaults use absolute and relative tolerance `1/10000000`; the native
evidence scope is `klause-ir-exact-v1`. Both rounds reproduce these work/progress outcomes; round one follows the
warmup and reverses layout order. All sixteen preparations admit, with at most
815,592 work and 21,129,260 modeled bytes. Each consultation sequence owns one
scoped solver and closes it to zero live owners.

| Source | Identity rows / entries | Equality rows / entries | Recorded pivots, identity / equality | First limiting phase or result |
| --- | --- | --- | --- | --- |
| cap6000 | 2299 / 48539 | 2176 / 48243 | 349 / 349 | Both complete with the same checked native source bound |
| rentacar | 10548 / 80230 | 5515 / 40772 | 174 / 321 | Both stop at floating work, without coordinates or a bound |
| misc04inf | 1852 / 23762 | 1545 / 17073 | 879 / 1021 | Floating work / pivot limit, without coordinates or a bound |
| p0033 | 15 / 98 | 15 / 98 | 16 / 16 | Both complete with the same checked native source bound |

Compaction reduces cap6000 floating work from 90,374,706 to 85,852,558 and
certificate work from 1,196,843 to 1,184,285, with no primal recovery. Rentacar
advances further under the same work allowance; misc04inf reaches its pivot
allowance at 142,364,117 work. Maximum observed fill is below 1.064 and density
below 0.00165. These observations identify numerical progress as the remaining
limit on the two large sources; they do not establish general fill bounds or a
wall-time speedup. The paired policy remains opt-in for unconditional templates.

The separate cap6000 native source lift and recheck takes 1.87–1.91 seconds and
about 8.30 GB of measured thread allocation in round one, versus 90–94 ms for
guidance and 25.4–25.7 MB of modeled engine certificate storage. That verifier
cost is outside the engine certificate allowance and is not an end-to-end resource
bound. Native source rechecks pass for cap6000 and p0033; these are not independent
original-MPS tolerance proofs. The retained consumer's separate
[gr4x6 certificate qualification](https://lab.eignex.com/jobs/8068), described
below, exercises bounded rational dual reconstruction and inherited bound closure.

[Control 8073](https://lab.eignex.com/jobs/8073) at `56da14bf` includes that merged
dual schedule and structured declared/effective semantics. It reproduces the
four-source pivot counts, floating stops and checked bounds; the cap6000 native
source recheck allocates 8.30 GB and takes 2.19–2.21 seconds in round one.
[Matched capture 8075](https://lab.eignex.com/jobs/8075) at `18cf97c3` reuses each
immutable source-context view within a verification run. Its cap6000 rechecks
retain the same exact bound, with 122.6–122.7 MB of measured thread allocation and
39–49 ms in round one. All four sources retain their numerical outcomes and
recorded pivot counts. The proof traversal allowance, public defensive snapshots
and source authority are unchanged. This single warmed pair measures the native
recheck phase, not a general whole-solve speedup or physical memory bound.

[Phase capture 8142](https://lab.eignex.com/jobs/8142), at `1c1649e3`, retains the
four frozen sources and existing floating limits on Mac/aarch64. Both large roots
remain in primal feasibility repair: rentacar has 251 zero steps in 323 iterations,
and misc04inf has 718 in 1024. There are no artificial-bound starts or numerical
recovery triggers. Their basis work is split almost equally between RHS FTRAN,
feasibility-gradient BTRAN and entering FTRAN.

[Long-step comparison 8149](https://lab.eignex.com/jobs/8149), at `7a987b4c`,
changes only the feasibility ratio in matched identity/equality layouts.
In the warmed equality layout, the last sampled scaled infeasibility falls from
207151.69 to 171981.42 on rentacar and from 2516.85 to 183.98 on misc04inf.
Zero steps fall from 251 to 106 and from 718 to 318. The added breakpoint work
leaves 291/984 pivots, against 321/1021; both candidates reach floating work.
Neither produces coordinates or a checked bound.

[Value-update comparison 8153](https://lab.eignex.com/jobs/8153), at `71850ef2`,
retains long steps and compares fresh RHS solves with periodic refreshes.
Rentacar advances from 291 to 386 pivots at 150M work; its last sampled scaled
infeasibility falls to 160454.41. Misc04inf reaches its 1024-iteration limit at
120524727 work, with 1020 pivots and sampled infeasibility 172.94. RHS FTRAN
charges fall from 40.23M to 7.25M and from 39.68M to 5.59M. Rentacar's additional
pivots raise measured guidance thread allocation from 114.2 MB to 152.0 MB;
misc04inf rises from 184.2 MB to 191.1 MB. These are instrumented phase allocations,
not RSS or a speedup claim. Cap6000 and p0033 retain identical pivots, work and
checked native source bounds in both comparisons. All owners close to zero;
observed sparse fill remains below 1.064 and density below 0.00165.

Integrated factorial [8160](https://lab.eignex.com/jobs/8160), at `82384c9e`,
repeats the original-limit equality comparison after rebasing current main.
Both first dual-feasibility failures are free singletons on fixed logical rows:
column 9557 for rentacar and 4897 for misc04inf. The completed control bounds and
the combined ratio/value-update progress repeat.

The separate four-times-funded [8161](https://lab.eignex.com/jobs/8161) uses the
same revision with 600M work and 4096 iterations. Misc04inf reaches floating
candidates in both the legacy and combined arms, at 300225414/272080437 work;
both bound certificates decline as `CANDIDATE`. Rentacar remains in feasibility
repair: the combined arm takes 1532 pivots against 1246, but its last sampled
infeasibility is worse, 150129.16 against 130734.68, with fewer violated basics,
1240 against 1399. The original-limit gain is not uniform under larger funding.
These observations motivate the separately controlled singleton opening.

[Opening comparison 8163](https://lab.eignex.com/jobs/8163), at `34c662be`,
charges its singleton pivot to the original limits. In the equality layout,
misc04inf's last sampled infeasibility falls from 172.94 to 114.19 at 120518497
work and 1022 recorded pivots. Rentacar retains its sampled infeasibility and
386 pivots, with 72991 additional work. Identity layouts remain unchanged, and
both completed control bounds pass the same native source check.
[Funded opening comparison 8166](https://lab.eignex.com/jobs/8166) keeps the
600M/4096 setting separate: misc04inf reaches floating candidates at
272080437/267223843 work without/with the opening, and both certificates decline
as `CANDIDATE`; rentacar's sampled infeasibility is unchanged. The supported
result is stronger bounded numerical progress with sparse factors. Source
witnesses, root proof completion and general speedups are not established.

[Certificate capture 8207](https://lab.eignex.com/jobs/8207), at `b397fc8f38`,
reproduces the completed misc04inf candidate after 2222 pivots. Its first refused
side is structural column 186's missing upper bound, at exact reduced cost
`-37/36893488147419103232`. [8256](https://lab.eignex.com/jobs/8256), at
`884965884e`, records common denominator 115362244805515 above the schedule's
44079937 limit. Independent entry reconstruction supplies a complete vector but
still fails exact reduced costs; a floating optimum supplies no bound.

[Basis repair 8283](https://lab.eignex.com/jobs/8283), at `bc4b93dc3c`, uses the
same four source hashes, Mac/aarch64, Java 25.0.4.1, 6 GiB heap, 600M/4096 floating
policy and 2M/64 MiB certificate allowance. After logical elimination, peeling
recovers 955 singleton equations and defers 105 variables, leaving 45 rows in three cores;
the largest is 41. All three exact solves cost 75,591 factor work and 5,199,992
modeled bytes. Complete certification costs 1,074,843 work and 24,633,954 modeled
bytes at 164 scalar bits, and its exact bound passes the original native source
lift/recheck. Cap6000 and p0033 retain identical checked bounds and certificate
work; rentacar remains a numerical feasibility stop. The misc04inf source check
takes 37 ms and 81.4 MB of measured thread allocation outside the engine allowance.
These instrumented captures establish a checked bound, not a source witness,
integer optimality, independent MPS proof or general speedup.

[Retained consumer 8288](https://lab.eignex.com/jobs/8288), at `50a242e6e9`,
consumes the same exact bound, approximately 2656.424000168177, and passes the native
source recheck with one operation and preparation. Its floating work remains
267,223,843; the caller ledger separately records 1,074,843 certificate work.
Cap6000 and p0033 retain their source-checked bounds. This reuses the funding
qualification in [8258](https://lab.eignex.com/jobs/8258); it establishes certificate
consumption from the completed numerical basis.

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

### Tuple packing capacities

Retain finite `+tuple-packing` as an opt-in representation for exclusively owned
Table partitions of plain AllDifferent; [presolve](../docs/07-presolve.md#structural-reductions)
owns its admission and reconstruction. Small controls cover the disjoint mandatory-value
counterexample, a certified shared-capacity LP refutation with no mandatory individual
value, sibling retraction and exact source multiplicity.

[8235](https://lab.eignex.com/jobs/8235), at `4c79a3697b`, compares default CP,
packing CP, explicit hole-probing CP and default mixed search on all fourteen
Pentominoes sources: P1, 20 seconds, seeds 3/17. All 112 cases finish and all 38
reported witnesses pass independent original-XCSP3 checking. Sixty-four cases run
on Mac/aarch64 and 48 on Linux/amd64; each problem's arms share a host. Sources,
runtime options and installed bytes are retained per case; timings are not pooled
across platforms. The four arms find 13/11/11/3 witnesses out of 28 respectively.

Packing admits 03-20, 04-15, 05-12 and 06-10, with 1,236/1,696/1,936/2,056
choices and 72 rows each. The ten other sources retain outside symmetry-order
readers and decline. Grouped source arguments repeat piece Tables on larger
boards; counting distinct relation payloads does not count those placements.
On the eight admitted cases, packing finds six witnesses versus default CP's eight:
06-10 loses feasibility in both seeds. On Mac, 03-20 first feasibility changes from
16.51/16.91 to 7.35/13.22 seconds, while 04-15 changes from 1.10/1.13 to
2.72/3.41. Packing preparation costs 176–435 ms versus 42–91 ms in these admitted
controls. On 03-20 seed 3, propagation work changes from 853.1M to 62.3M visits
and whole-CLI peak RSS from 323 to 237 MiB. These observations establish no
uniform speedup or allocation improvement. Bounded row-aware placement branching
is the consuming next step before reconsidering default admission; preserving
outside source readers requires a separate coordinate channel.

### Local-search trajectories

`bench.profile.LocalSearchTrajectory` takes `suite=ID problem=NAME sha256=HEX
recipe=LABEL seed=N moves=N` for a hash-pinned minimizing XCSP3 source. It compares
protected and substituted objective coordinates through mixed-route preparation,
then runs the selected local-search recipe alone with a fixed allowance counting
moves and restarts, and no shared incumbents. The seed is the worker seed,
including its portfolio index.
Optional `sliceMillis=N sliceWork=N` repeatedly resumes the same handle under
those cooperative allowances; omitted allowances are unbounded. Timed slices
depend on capture overhead and host speed and do not reproduce portfolio allocation.
Optional `greedy=false` omits the walk's generic greedy initializer; the separate
initialization capture and coordinate trace still retain that pass as a control.
A move source that emits no candidates records source-coordinate assignments,
ordered violated factors and exact degrees before the first 256 candidate collections
and every 512 thereafter. A separate initialization capture omits greedy repair,
retaining the initial assignment and RNG integer fields without extra random draws.
That run stops at the observer before candidate generation; its retained state then
traces the production greedy pass one coordinate at a time through reflection.
The diagnostic stop supplies no solver verdict or worker-failure evidence.
`repairs=true` adds an observed repetition of the same fixed-work walk and checks
its final witness, objective, pick count and non-timing work/restart/stall counters
against the unobserved repetition. At most 32 repair/descent attempts retain their
center and each phase's working and reconstructed source assignment. Distance
counts named source inputs excluding frontend-declared definitions; auxiliary
changes and approximate real displacement remain separate. Censored endpoints
receive no basin classification. The declared recipe and worker seed bind the
descent rule, without claiming exhaustive coverage of its sampled moves.
`controls=true` compares individual freedoms, complete small mandatory
AllDifferent, Cumulative and Diffn scopes
and four size-matched samples at up to four feasible endpoints, using ordinary
CP minimization with the complement pinned. Each repair has 10,000 decisions and
the control scan shares ten seconds; unfinished scans grant no exhaustion claim.
The capture reports native original-source checks separately from independent
witness checking and carries no unpinned proof claim. `center=v0,v1,...` admits
only an unchanged finite coordinate frame without declared definitions, disables
presolve and uses the protected objective. `recipe=source-coordinate-descent`
selects the strict objective-coordinate control; ordinary catalog recipes retain
their structured sources. Fixture expectations live beside
[finite repair controls](smoke-corpus/finite-repair/README.md).
`batch64=<base64 JSON array of argument arrays>` captures up to eight requests
in numbered directories under one profile job.
Model keys, domains, objectives, source bytes and
build identity remain alongside the walk. Inputs admit at most 64 MiB,
1,024 Boolean/integer coordinates and 4,096 factors. Hint construction uses the production
mapping through reflection and fails on an internal shape mismatch. Each arm has
a sixty-second outer deadline; cancellation is recorded. These instrumented walks
isolate local trajectories; they establish neither default portfolio equivalence,
throughput, native proof nor independently checked source feasibility.

### Table profiling

`bench.profile.ExtensionalCompositionProfile` accepts `suite=ID problem=NAME
sha256=HEX` for one XCSP3 source. It retains original bytes and build identity and
reports table/MDD scope shapes, shared positions, bounded composition work,
current-thread allocation and resulting diagram records. Full-source and isolated
first-diagram overlaps use the production root-composition mechanism. Three
iterations include factor-view construction and cold preparation; these instrumented
observations establish neither a source verdict nor ordinary search throughput.
Use `presolve=off,+extensional` for isolated CLI comparisons and
`presolve=default,+extensional` alongside the default presolve passes.

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

### Bounded retained integer probing

Default metered finite satisfaction preparation funds one retained root, 128 probe
or repair calls, another 32 calls per completed refutation, and at most 50 million
work units or one eighth of remaining phase work. Explicit probing remains
exhaustive; unmetered calls do not fund the pilot.

[8243](https://lab.eignex.com/jobs/8243) compares the initial pilot with `0b9f22b574`
on ten original, satisfiable negative and held-out XCSP3 sources, CP/LP-off and
ordinary mixed/default-LP, P1, seeds 3/17 and 20 seconds on Linux/amd64. All 80
records return; all 27 feasible witnesses pass independent source checking.
ChainReaction-20-020 gains native refutations in both routes and seeds, while
Pentominoes remains UNKNOWN. Mixed satisfiable ChainReaction-20-025 at seed 3
slows from 1.47 to 18.76 seconds.

[8311](https://lab.eignex.com/jobs/8311) compares the complete retained-root pilot
`7400de1ad8`, its otherwise identical 8-million stop `86850d2f8d`, and integrated
default `1bd3cb70b6`. It uses the same ten sources, ordinary mixed/P1/default LP,
20 seconds, seeds 3/17 and three repeats on Mac/aarch64. Original hashes, runtime,
options and non-solver dependencies match. The lab returns 179 records and accepts
all 75 witnesses against their original sources. One Nonogram control is
interrupted by the lab; the completed job refuses retry, and its unmatched pair
is excluded. Repeats of two seeds are not independent samples. Native UNSAT claims
have no independently checked proof certificate.

The 50-million stop refutes ChainReaction-20-020 in all six runs, in 1.10–1.43
seconds; default and 8 million remain UNKNOWN. Its 515 calls narrow 51 columns.
Nonogram's checked witnesses take 1.07–1.22 seconds versus default's 2.79–7.33;
8 million starts no probes and takes 2.90–7.39 seconds. The retained root doubles
ChainReaction root-construction entries from 728 to 1456 across 515 calls. One
matched whole-CLI resource receipt changes peak RSS from 552288 to 359104 KiB
and wall time from 20.11 to 1.16 seconds; this includes the shorter search and
does not measure probe allocations.

Costs remain visible. Satisfiable ChainReaction takes 5.69–8.85 seconds at 50
million versus default's 0.77–1.62; 8 million takes 0.84–2.03. CarSequencing-75
returns two checked witnesses under default and one under each pilot: 50 million
has one paired gain and two losses, despite no narrowed columns. Pentominoes,
Eternity, NurseRostering and CarSequencing-80 remain UNKNOWN; Nurse preparation
rises from 125–126 to 238–240 ms. Keep the 50-million stop for bounded deduction
and refutation coverage, with these latency and feasibility costs; the cheaper
stop loses both ChainReaction refutations and Nonogram's search benefit. These
controls establish no general speedup.

[8321](https://lab.eignex.com/jobs/8321) compares the rebased implementation
`4bcf201d7e` with `02e78c79dc` after finite presolve and search-catalog integration,
using the same ten sources and options, with one repeat per seed on Mac/aarch64.
All 40 records return and all 22 witnesses pass independent source checking;
original hashes, runtime, options and non-solver dependencies match. Both native
ChainReaction refutations remain, in 1.10/1.50 seconds versus default UNKNOWN.
Nonogram takes 1.08/1.26 seconds versus 4.06/6.11. Satisfiable ChainReaction still
costs 5.73/9.09 seconds versus 0.78/1.40. Both CarSequencing-75 seeds are feasible
under both arms. Single paired witness gains on Eternity and CarSequencing-80
establish no stable recovery; Eternity reports 133 narrowed columns, while
CarSequencing-80 reports none. This integration
run has no paired feasibility loss; it does not remove the losses in the separate
three-repeat work-stop comparison.

### Exact source equality blocks

[Capture 8115](https://lab.eignex.com/jobs/8115) retains the tagged exact rows of
the seven equality controls and original `lra-rational`, including polynomial-backed
and wide rows. The guarded control supplies two unconditional equalities and two
excluded activated rows. [8137](https://lab.eignex.com/jobs/8137) captures four
original QF_LRA sources: the Heizmann and routing sources have no unconditional
equalities, cooking21 has one, and driverlogNumeric has 46 with 457 entries.

[8109](https://lab.eignex.com/jobs/8109) compares fixture-only `1468aae467` with
`53e2866255`: 35 inputs, default and isolated `off,+dependent-rows` preparation,
separate default-policy controls, P1, seed 3, three repeats, 10 seconds and debug
enabled on Mac/aarch64. All 315 paired feasibility, proof-status and exact-objective
outcomes match. Original hashes, runtime, options and non-solver dependencies match.
The checker accepts 198 witnesses; 36 mixed witnesses are indeterminate from a
Dolmen type error and 216 witnesses have unsupported objective, MPS syntax or
decimal checks. The 72 native UNSAT claims have no independent proof certificate.

The five dependent SAT controls each lose one equality; the guarded alternative
remains satisfiable. The real contradiction is discharged during preparation:
default median preparation/whole-CLI times change from 26/358 to 15/298 ms.
Polynomial SAT preparation/CLI medians instead rise from 24/465 to 30/476 ms.
Original `miplib/fixnet-5000` loses one row but remains UNKNOWN; its default
preparation median rises from 241 to 366 ms, within a 10-second solve budget.
Retain exact representation and earlier refutation with these preparation costs;
row reduction alone establishes neither search benefit nor a general speedup.

### Joint extensional root composition

Bounded root composition is opt-in. Ground-table semijoins and table-to-MDD
products preserve joint restrictions through ordinary Table/MDD propagation and
explanations.

[Lab 8129](https://lab.eignex.com/jobs/8129), at `51ff71ae08` on Mac/aarch64,
Java 25.0.4.1, retains the original WordDesign2-words-mdd-015_c23 source hash and
build identities. Its 15 eight-column tables each have 17,440 rows; its 105
sixteen-column MDDs each have 525,721 states and 1,978,996 transition records.
Each diagram overlaps two tables at positions 0–7 and 8–15. These diagrams exceed
the bounded input admission, so every sampled intersection retains its inputs.
The full-source call spends 4,064 visits, replaces no factor and takes
0.36–2.27 ms with 308,872–589,264 measured thread-allocated bytes across three
iterations, including factor-view construction and cold preparation. JFR phase
observations establish neither a source verdict nor ordinary solve throughput.
No WordDesign recovery or larger-product admission follows from this capture.

[Lab 8169](https://lab.eignex.com/jobs/8169), at `cc93562ee0`, isolates `off`
versus `off,+extensional` on Linux/amd64 spot, CP/satOptimized/P1, 15 seconds,
seed 3 and three repeats. All five source hashes, build, runtime and JVM options
match between arms. All 30 cases complete without errors. Table/table and
table/MDD contradictions refute in source presolve, avoiding two native failures
and 194/238 units of CP propagation work respectively; charged presolve work
falls from 852/1647 to 29/47. These are native refutations, without independent
proof checking. All six satisfiable-control witnesses pass the external XCSP3
checker. Both arms use two nodes there, while composition raises presolve work
from 2,178 to 5,129 and CP propagation work from 257 to 262.

Both original word sources remain unknown without witnesses. WordDesign uses six
nodes in both arms and completes its bounded decline with 4,198 charged units;
its root CP work is unchanged. WordSquare uses 29–31 nodes without composition
and 29–30 with it. Its pass spends 202 units without a rewrite, while total
charged preparation work increases from 611,670,614 to 1,223,317,369. Deadline
prefixes establish no throughput equivalence. Retain the exact root inference and
bounded fallback as an opt-in capability, with these preparation costs and no
WordDesign recovery or general speedup claim.

[8130](https://lab.eignex.com/jobs/8130) identifies payload-wide entry charging
before relation admission. The self-metered pass charges its own visits and uses
unit-weight scheduling normalization. The scheduling failures in
[8164](https://lab.eignex.com/jobs/8164) are errors and supply no UNKNOWN verdict
or search evidence.

### Cargo objective-coordinate trajectories

[Confirmation 8203](https://lab.eignex.com/jobs/8203) retains the frozen original
Cargo-09-1s-18_c24 source at default mixed/P1/30 seconds on Mac/aarch64. Seed 3
finds three independently accepted final witnesses at `f59091a21acf` and none at
`0b9f22b57412`, without worker faults. [Brackets 8212](https://lab.eignex.com/jobs/8212)
and [refinement 8279](https://lab.eignex.com/jobs/8279) isolate exact objective
substitution at `9a38fd5417`: prepared factors fall from 146 to 133. Intermediate
historical builds delete only a broken unused benchmark profile; their production
solver and CLI sources remain identical to the historical revisions.

[Capture 8277](https://lab.eignex.com/jobs/8277) and
[coordinate capture 8284](https://lab.eignex.com/jobs/8284) compare both objective
representations at worker seed 6. Pre-greedy slots 0–314 and all six RNG fields
match, as does the 328-coordinate order. At zero-based entry 49, source `rt[19]`,
the protected value stays 43439 while substitution selects 46194 with identical
RNG state. Protected grading is 611, with alias 326 equal to 8221, versus 530 after
substitution. The first RNG divergence follows entry 68, an eliminated objective
slot. Inactive-coordinate probes are secondary; altered infeasible grading drives
the first changed repair decision. Exact substitution preserves source semantics;
these controlled native observations establish neither a correctness defect nor
default-portfolio equivalence.

The ordinary controls use matched sources, default allowances and repeated seeds
within each job. Linux and Mac timings are not pooled. Decisive negative results
remain relevant when selecting the representation:

| Comparison | Candidate | Admission limit |
|---|---|---|
| [8276](https://lab.eignex.com/jobs/8276), [8289](https://lab.eignex.com/jobs/8289) | Inactive-coordinate and broad boundary repair | No original seed-3 recovery; the current-base broad variant drops Cargo coverage from two to one and worsens six Carpet objectives. |
| [8307](https://lab.eignex.com/jobs/8307) | Blocked-separation fallback | One of three original cases; loses two seed-17 witnesses and worsens all three seed-41 objectives. |
| [8315](https://lab.eignex.com/jobs/8315) | Omit generic greedy initialization | No original recovery; total Cargo coverage falls from six to five. |
| [8326](https://lab.eignex.com/jobs/8326) | Primary boundary repair and structural admission | At most one original witness; two seed-41 objective losses remain. |
| [8334](https://lab.eignex.com/jobs/8334) | Balanced sibling discovery, alone or combined | Every arm misses the original three cases despite gains on CarSequencing. |
| [8341](https://lab.eignex.com/jobs/8341) | Global objective protection | Restores all three original cases but loses all six seed-17/41 witnesses retained by both rewritten controls. |
| [8351](https://lab.eignex.com/jobs/8351) | Private protected view in the primary slot | Two original witnesses arrive only at 27733–28171 ms. |
| [8352](https://lab.eignex.com/jobs/8352) | Persistent private view in the full slot | Restores all three at 7263–7588 ms but worsens every seed-41 objective. |

Frozen ALNS failures retained in 8276 do not explain the fault-free original loss.
[Isolated CLI bridge 8320](https://lab.eignex.com/jobs/8320) produces three checked
35682 witnesses under an explicit LS recipe, without establishing default recovery.
[Capture 8330](https://lab.eignex.com/jobs/8330) reproduces that fixed walk at the
integrated base; nominal moves alone cannot establish portfolio admission.
[Phase comparison 8353](https://lab.eignex.com/jobs/8353) retains three original
witnesses when the protected full view retires after the first global incumbent.
It improves all three seed-41 objectives against the persistent view, while still
losing two against the current control.

[Final recovery 8356](https://lab.eignex.com/jobs/8356) compares production revision
`deba36be53` with frozen main `b461a3e62d`: seven original/held-out identities,
seeds 3/17/41, three repeats, default mixed/P1/30 seconds on Mac/aarch64. All 50
reported final witnesses pass independent checking, with no worker faults.
Cargo-09 coverage rises from five to nine of nine. All three original seed-3 cases
report first feasibility at 8907/9044/8994 ms; final objectives 13509/13244/13862
pass independent checking. Seed 17 retains three witnesses versus two, with both
paired feasible objectives worse. Seed 41 retains all three, with two better and
one worse objective. CarSequencing and CarpetCutting retain all nine witnesses;
Carpet objectives improve in three pairs and worsen in six, with zero native
optimum claims versus one in the control. Carpet admits no private view. Cargo-04,
Cargo-07, SquarePacking and PegSolitaire remain unresolved in both arms.

Cargo-09 median preparation rises from 290 to 518 ms and metered preparation work
from 13,584,596 to 28,172,729 within the same shared allowance. Two completed bakes
report aggregate factor count 279 versus one reporting 133; aggregates do not
establish model identity. The private handle retires through ordinary scheduler
closure after the first global witness, preserving its counters.

[Preservation 8357](https://lab.eignex.com/jobs/8357) completes 96 matched cases at
the same revisions: eight MPS/Distinct identities, seeds 1/3, three repeats and
default P1/30 seconds. All 72 reported final witnesses pass independent checking,
with no worker faults. Each arm retains six native optimum claims on cap91
(`12746375/16`), enigma (`0`), gr4x6 (`4047/20`) and p0033 (`3089`), plus six native
BlockedQueens-28 UNSAT claims. The satisfiable BlockedQueens-28 control retains six
checked witnesses; scarce BlockedQueens-56 stays unresolved. Dcmulti retains six
checked witnesses per arm, with two better and four worse candidate objectives.
Every preservation case uses one bake and no private view. Native proofs remain
separate from independent witness checks.

Both final comparisons retain original source hashes and matching nonempty runtime,
options and non-solver dependency receipts. Compiler configuration is identical;
the records do not report compiler fingerprints. Coverage recovery coexists with
the listed quality and proof-rate tradeoffs; no general throughput claim follows.

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

### XCSP3 recovered affine distinctness

Retain bounded complete-group recognition into exact affine `Distinct` over
original integer coordinates. Matching/Hall propagation transfers bounds and holes
directly to source domains, with historical source reasons and no materialized
channels. The [format contract](../docs/04-finite-formats.md#arithmetic-relations)
owns admission.

[8047](https://lab.eignex.com/jobs/8047) compares pairwise main `2f228a33b2`,
linear channels `8e803014ee` and bounded Element channels `cca1b1ad8e` on all
eight BlockedQueens sources and Fischer, GracefulGraph, QuasiGroup and
SportsScheduling holdouts. CP/LP-off uses one conflict-driven arm, P1, 2000 nodes,
a 20-second deadline, seeds 1/2/3 and three repeats on Mac/aarch64. External time
measures ordinary CLI runs with debug statistics and no JFR. All 81 SAT witnesses
pass original-source checking; source, runtime/options and dependency hashes match.
Counters agree across seeds and repeats within each arm, rather than representing
independent search paths. Source suffixes below denote `BlockedQueens-<suffix>_c22`.

| Source suffix | Nodes: pairwise / linear / Element | Median CLI seconds: pairwise / linear / Element |
|---|---|---|
| 28-1449787798 | 2000 / 2000 / 2000 | 1.80 / 2.66 / 2.68 |
| 28-1449787894 | 1310 / 428 / 1294 | 1.30 / 0.96 / 1.82 |
| 48-1721237620 | 2000 / 2000 / 2000 | 2.27 / 3.37 / 3.38 |
| 48-1721237798 | 2000 / 2000 / 2000 | 2.30 / 3.37 / 3.30 |
| 50-1642398261 | 2000 / 2000 / 2000 | 2.36 / 3.41 / 3.37 |
| 50-1642399343 | 2000 / 2000 / 2000 | 2.47 / 3.82 / 3.57 |
| 56-1860436781 | 2000 / 2000 / 2000 | 2.61 / 3.95 / 3.81 |
| 56-1860437025 | 2000 / 2000 / 2000 | 2.54 / 4.01 / 3.47 |

Only the second source has a witness at this allowance; the other seven remain
unknown. All four holdouts retain search counters and nearly equal CLI medians.
The hard 56-source integers increase from 56 to 166 and factors from 5815 to 5927;
median peak RSS changes from 225152 to 235984 KiB with Element channels. These
are representation and trajectory tradeoffs, with no general throughput gain.

[8053](https://lab.eignex.com/jobs/8053) uses the same sources and revisions on
ordinary mixed/P1, ten seconds, seeds 1/2/3 and one repeat. All 28 SAT witnesses
pass source checking. Pairwise and Element arms retain the small UNSAT source's
native refutation in all three seeds; linear channels leave all three unknown.
Element finds a checked witness for `56-1860437025` at seed 1, with both controls
unknown in all three seeds. `56-1860436781` remains unknown in every arm, and
heldout feasibility/proof outcomes agree. Native refutations are not independently
proof-checked; one ordinary-route repeat establishes no timing speedup.

These materialized-coordinate controls retain collective interval and noncontiguous
deductions with exact source semantics. They do not establish hard-family recovery
or isolate matching from representation and worker effects.

[8091](https://lab.eignex.com/jobs/8091) compares materialized parent `e77e09a7e4`,
source-coordinate projections `4f4f50fa76` and its ground-only CP control
`2041301437` on the same twelve sources: CP/LP-off, one conflictDriven arm, P1,
2000 nodes, 20 seconds, seeds 1/2/3 and three repeats, without JFR. All 324 cases
return and all 81 SAT witnesses pass independent original-source checks. Source
hashes, runtime/options and non-solver dependency hashes match on Mac/aarch64;
compiler identity is unreported. CP counters repeat across seeds and repetitions,
so these are timing repeats rather than independent search paths.

On `56-1860436781`, source integers fall from 166 to 56 and factors from 5927 to
5817. Median subprocess elapsed falls from 3.864 to 2.878 seconds at 2000 nodes;
failures change from 4306 to 4151 and mean learned size from 59.07 to 55.28 literals.
The ground-only control takes 2.726 seconds with mean learned size 59.15.
Matching is slower than ground checking on all eight queen sources. On the small
SAT source, materialized/matching/ground nodes are 1294/856/268 and median elapsed
is 1.924/1.189/0.602 seconds. Collective inference has a cost and does not improve
every search prefix. Four holdouts retain their search counters and nearly equal
median elapsed. Different failures, depths and auxiliary root-fixing counts prevent
a pure per-node throughput claim; the seven capped queen sources remain unknown.

Coarse source-domain reasons in [8083](https://lab.eignex.com/jobs/8083) also reduce
cost, but [8084](https://lab.eignex.com/jobs/8084) loses all three small-source native
refutations and the parent's one checked 56-queen witness. Coarse-only matching is
not retained; larger roots keep exact source-domain fallbacks.
[8092](https://lab.eignex.com/jobs/8092) separates historical Hall-union
reasons from their combination with incremental source collision scoring on the
ordinary mixed/P1/10-second route. The combined arm retains all three small-source
native refutations; native proof labels are not independently certificate-checked.

[8125](https://lab.eignex.com/jobs/8125) qualifies atomic source repairs `582db383d7`
against the materialized parent on all twelve originals: mixed/P1, ten seconds,
consecutive seeds 1–10 and one repeat. All 240 commands return and all 61 SAT
witnesses pass original-source checks. The small UNSAT source has 8/10 native
refutations with materialization and 10/10 with source projections; the four
holdouts retain verdict coverage. On `56-1860437025`, the parent finds a checked
witness at seed 1 while source projections remain unknown in all ten seeds.

[8165](https://lab.eignex.com/jobs/8165) measures this remaining gap over the
prespecified complete seeds 1–100, once per arm, with the same ordinary settings.
All 300 commands return and all five SAT witnesses pass original-source checks.
Each seed's three-arm group matches source hashes, runtime/options, non-solver
dependencies and platform: 71 groups on Mac/aarch64 and 29 on Linux/amd64 Spot.
Compiler identity is unreported; earlier overlapping seeds are not additional
independent trials.

| View | Revision | Checked witnesses / 100 seeds |
|---|---|---|
| Materialized | `e77e09a7e4` | 4: seeds 1, 32, 36, 84, all on Mac |
| Incremental source counts | `d9a304064a` | 0 |
| Counts and atomic source swaps | `582db383d7` | 1: seed 70, on Linux |

Retain atomic source repairs with the lower representation cost and observed
ordinary feasibility on this original input. Witness frequency is lower: all four
parent winning seeds remain unknown with source swaps, while both controls are
unknown at seed 70. These data establish neither identical walks nor a uniform
ordinary-route speedup. The CP projection is unchanged by the repair additions;
the cost control above does not isolate local-search operator throughput.

#### Covered affine source rows

[8209](https://lab.eignex.com/jobs/8209) compares retained rows at `32cc4dfff6`
with exact covered-row replacement `d8fb41ca71` on the same twelve originals:
CP/LP-off, one conflictDriven arm, P1, 2000 nodes, 20 seconds, seeds 1/2/3 and
three repeats. All 216 cases return and all 54 SAT witnesses pass independent
original-source checks. Every pair matches source hashes, runtime/options,
non-solver dependencies and Mac/aarch64 platform; compiler identity is unreported.
External CLI time/RSS uses no JFR and collects no allocation estimate.

On `56-1860436781`, source integers stay at 56 and factors fall from 5817 to 2737.
Median frontend load changes from 103 to 96 ms and finite engine bake from
9.9 to 6.6 ms; bake work falls from 123323 to 51603 units.
Propagation work falls from 101,831,608 to 48,255,150, failures from 4151 to 4068,
and peak depth rises from 21 to 24 at 2000 decisions. Median physical CLI wall
time changes from 2.97 to 2.35 seconds and peak RSS from 240336 to 236096 KiB.
All eight queen wall-time medians decrease, but changed failures and depths
prevent a pure per-node throughput claim. On `56-1860437025`, depth falls from
31 to 28 and failures rise from 4280 to 4290; both arms remain unknown.

The small SAT source uses 856 versus 747 decisions; all four holdouts retain
their CP work and verdicts. Counters repeat across seeds and repetitions within
each arm, so these are timing repeats rather than independent search paths.
This comparison predates numerical-companion integration and establishes no
ordinary mixed-route feasibility or proof recovery.

[8278](https://lab.eignex.com/jobs/8278) compares integrated main `0b9f22b574`
with rebased replacement `763470d49b` on all twelve originals: ordinary mixed/P1
defaults, ten seconds and consecutive seeds 1–10 once each. All 240 cases return
and all 62 SAT witnesses pass independent source checks. Small-source native
refutations increase from 6/10 to 10/10; those proofs are not independently
certificate-checked. The small SAT source and four holdouts retain verdict
coverage. On scarce SAT source `56-1860437025`, retained rows find seed 10 and
replacement finds seed 3, each missing the other's witness. Other queen sources
remain unknown. All 120 pairs match original hashes, runtime/options, non-solver
dependencies and Mac/aarch64 platform; compiler identity is unreported.

[8242](https://lab.eignex.com/jobs/8242) compares integrated main `0b9f22b574`
with rebased replacement `763470d49b` on scarce SAT source `56-1860437025`:
ordinary mixed/P1 defaults, ten seconds, consecutive seeds 1–100 once each.
All 200 cases return. Both arms find one independently source-checked witness:
retained rows at seed 70 and replacement at seed 3. Each arm misses the other's
successful seed; the materialized parent's four successful seeds remain unknown
in both arms. Equal observed witness counts establish neither equal walks nor
equal feasibility probability. Median aggregate CP decisions change from 3180
to 4714 and LS moves from 31618 to 71641, with changed portfolio trajectories.
All 100 pairs match original hashes, runtime/options, non-solver dependencies
and Mac/aarch64 platform; compiler identity is unreported.
Overlapping seeds in the ordinary family run are repetitions, not extra trials.
These results support lower preparation and propagation cost with observed
ordinary source feasibility and stronger bounded refutation coverage; they do
not establish uniform feasibility or identical search trajectories.

### Table and MDD historical domains

Retain bounded exact prefix/suffix path weights for deterministic MDD guidance
with distinct sequence variables; [search](../docs/08-search.md) owns admission,
undo and qualified edge-count fallbacks. Small-language tests distinguish suffix
mass and merged prefixes, restore sibling counts and cover interruption, aliases,
ambiguity, overflow and storage declines.
[8108](https://lab.eignex.com/jobs/8108) pairs `ee56127636` with core `c3a8a5f5c4`
on Linux/amd64: one conflictDriven/maxSD CP arm, LP off, P1, 15 seconds and seeds
3/17. All 20 commands return and all eight witnesses pass independent source
checks. Source hashes, runtime/options and non-solver dependencies match; compiler
identity is unavailable.

Every pair retains nodes, failures and depth. WordDesign2 `015` reaches six nodes
and held-out `045` two; both remain unknown. WordSquare reaches one/three nodes
by seed and remains unknown. CarSequencing retains its 3,917-node/1,990-failure
witness prefix, with elapsed ranges 7.55–8.23 versus 7.62–7.94 seconds. Table
Nonogram retains 614 nodes, taking 2.418–2.458 versus 2.428–2.514 seconds.
WordDesign peak RSS spans 882,056–908,668 KiB across both arms; allocations were
not collected. Equal seed prefixes are not independent trajectories. Adoption is
for bounded exact guidance capability, with no demonstrated hard-family recovery
or general cost improvement.

Retain selectively admitted `support-aware` CSP search beside ordinary complete
recipes; [portfolios](../docs/12-portfolio.md) owns its structural gate and cheap
declines. [8162](https://lab.eignex.com/jobs/8162) compares `1725d79c0e` with
`a660856bac` on Linux/amd64: nine frozen original/held-out sources, CP and mixed/P1,
15 seconds and seeds 3/17. All 72 cases return, all 36 pairs retain feasibility and
all 32 witnesses pass independent source checks. Paired source hashes, runtime,
options and non-solver dependencies match; compiler identity is unavailable.
[8119](https://lab.eignex.com/jobs/8119) retains the earlier admission prototype's
separate Mac/aarch64 and Linux/amd64 controls.

CarSequencing `75-02` reaches depth 437–441 instead of 383–415 across the four
CP/mixed pairs; `80-01` reaches 402–403 instead of 362–398. Both remain unresolved.
Eternity mixed depth rises from 29/32 to 37, while CP seed 17 falls from 40 to 37.
On Nonogram `039`, CP nodes change from 2,572 to 2,140 at seed 3 and from 2,454 to
2,565 at seed 17. Its mixed seed-3 run grows from 1,576 to 2,312 nodes and from
6.794 to 13.567 seconds, with peak RSS 263,340 to 315,260 KiB. Whole-process RSS
ratios span 0.849–1.197 across all pairs; allocations were not collected.

Nonadmitted BlockedQueens and WordSquare CP controls retain their palettes, but
elapsed scheduling still changes sampled paths. Equal seed settings establish no
trajectory independence. WordDesign has ancillary small Tables and is admitted;
it remains unresolved. Eight WordDesign/WordSquare mixed cases hit the roughly
50-second hard process cap without search statistics, leaving the stopped phase
unconfirmed. Native work includes discovery visits in the candidate, so raw work
totals are different currencies. Adoption is for bounded complementary coverage,
with observed search and cost tradeoffs and no general speedup or recovery claim.

Reject standalone `NoopStarted` counter filling in `66c224e6fc`.
[8198](https://lab.eignex.com/jobs/8198) pairs it with `0b9f22b574` on
Linux/amd64: the parent's explicit maxSD retains six checked Car80 witnesses at
the same 3,917-node prefix, while the prototype loses all six at 15 seconds.
Its Nonogram node reduction does not justify that loss. Relation-count availability
does not replace global propagation guidance; the provider remains a research variant.

Retain bounded `propagation-guided` CSP search beside cheap `support-aware` ordering;
[portfolios](../docs/12-portfolio.md#satisfaction-pools) owns admission and
[search](../docs/08-search.md) owns restoration and the local work allowance.
[8248](https://lab.eignex.com/jobs/8248) compares `373afe5346` with core
`bda32446e3` on Mac/aarch64: seven original sources, P1, 15 seconds, seeds 3/17
and three repetitions. All 252 cases return without arm failures, all 96 witnesses
pass independent source checks, and no accepted witness is lost across 84 paired
CP/mixed default controls. Source hashes, runtime/options and non-solver dependency
bytes match; compiler identity is unavailable. Later commits add tests, API snapshots
and merged propagation fixes without changing the selector or recipe implementations.

| Single recipe | Explicit maxSD | Bounded propagation guidance |
| --- | --- | --- |
| Car75 | 13,568 nodes, 11.599–14.433 s | 5,037 nodes, 4.372–5.626 s |
| Car80 | 3,917 nodes, 5.599–7.517 s | 4,991 nodes, 5.737–7.171 s |
| Nonogram 039 | 915 nodes, 3.971–4.300 s | 799 nodes, 5.678–6.223 s |

Each cell retains six checked witnesses at one identical prefix across both seeds;
these are not independent seed trajectories. Car75 spends 483 completed probes and
seven local cap hits; Car80 spends 457 probes and 32 hits. Default Car runs remain
unresolved. Car80 CP depth rises from 403 to 405–425 and mixed depth from 402–403
to 405, without establishing a portfolio verdict gain. Nonogram mixed defaults
cost 6.153–10.475 seconds instead of 4.455–6.627 despite retaining every witness.

WordDesign's bounded single recipe reaches 15–20 nodes instead of six, and WordSquare
24–35 instead of three; both remain unknown. WordDesign records 12–14 locally capped
orderings and no completed probes, explicitly declining its expensive fallback.
Default WordDesign/WordSquare remain unknown. Eternity's maxSD controls retain depth
37 without fallback probes. Nonadmitted QuasiGroup preserves default feasibility and the single-recipe
373-node witness prefix. Bounded native work includes residual-score visits, so raw
work totals have different currencies. RSS and allocations were not collected.
Adoption preserves bounded complementary guidance with measured costs, without a
general speedup or hard-family recovery claim.

Retain adaptive live tuple masks and bounded support-count ordering as consumers of
the shared index. [8055](https://lab.eignex.com/jobs/8055) compares `e5028472dd`
(the retained algorithms on parent `e467739f4e`, with maxSD exposed) and `e36eb235ac`
on Mac/aarch64, CP/LP-off, one conflictDriven arm, 2000 nodes, 20 seconds, seed 3
and three repeats. Both revisions run random ordering and maxSD; all 60 commands
exit successfully, and all 24 reported witnesses pass independent source checking.
Source hashes, runtime, options and non-solver dependency bytes match.

| Source | Probing maxSD control | Live-support maxSD |
| --- | --- | --- |
| WordSquare | 2 nodes, 20.13–20.24 s, unknown | 4 nodes, 20.97–20.98 s, unknown |
| Eternity | 1839–1879 nodes, 20.12–20.13 s, unknown | 2000 nodes, 3.39–3.47 s, unknown |
| WordDesign2 | 13–14 nodes, 20.12–20.15 s, unknown | 11–12 nodes, 20.12 s, unknown |
| Table Nonogram | 348 nodes, 2.51–2.64 s, valid witness | 614 nodes, 1.39–1.41 s, valid witness |
| Regular Nonogram | 398 nodes, 0.89–0.91 s, valid witness | 398 nodes, 0.89–0.91 s, valid witness |

Random-order Eternity preserves 5644 failures and depth 27 at 2000 nodes, with
similar process times; random Table/Regular Nonograms preserve nodes and failures.
Random WordDesign reaches 70–77 nodes instead of 67–68, with different failures.
Support ordering changes trajectories: Eternity's depth reaches 37 instead of 19,
while WordDesign reaches fewer nodes. WordSquare support ordering raises peak RSS
from 1442944–1452784 to 1624272–1723856 KiB; its deadline overrun is retained as a
cost, not a solved-source gain. Cold root propagation also grows from 19–25 to
204–243 ms on WordSquare, and 56–62 to 107–118 ms on WordDesign. The Table mechanism
uses Compact-Table-style mask invalidation with a retained STR2 prefix; the MDD
comparison retains CSR reachability and adds indexed symbol cuts. It does not
evaluate a complete Compact-MDD replacement.

Reject the bounded Compact-MDD continuation evaluated in
[8066](https://lab.eignex.com/jobs/8066) and [8070](https://lab.eignex.com/jobs/8070).
It retains shared CSR root preparation, then uses reversible sparse live-arc words,
shared sparse/dense symbol/head/tail supports and checked incoming/outgoing
residues. Admission covers unweighted diagrams with at most 4096 enumerable root
values per position and 32 MiB primitive index/state estimates. The second variant
limits propagation and support filtering to changed arc layers. Both experiments
pair parent `e36eb235ac` with the prototype (`0804c57cab`, then `dc17a5a896`) on
Mac/aarch64: CP/LP-off, random ordering, 2000 nodes, 20 seconds, seed 3 and three
repeats over the same five sources. All 60 commands return, all 24 reported
witnesses pass independent source checks, and source/runtime/options/non-solver
dependency identities match within each comparison.

The full-pass prototype reaches six WordDesign nodes instead of 70–77; the
changed-layer variant reaches 8–9 instead of 70–77. All remain unknown at
20 seconds. Changed-layer peak RSS is 1620016–1632864 KiB instead of
1191856–1203760 KiB. Table/Regular holdouts preserve nodes and failures, while
WordSquare remains unknown. The prototype passes JVM/native historical-reason,
cancellation and sparse-arc restoration controls, but its measured continuation
cost does not justify replacing CSR. This rejects the tested bounded variant;
other Compact-MDD implementations and table-to-sMDD conversion are not covered.

Retain cooperative Table/MDD visits and incomplete-reason suppression.
[8046](https://lab.eignex.com/jobs/8046) pairs `2f228a33b2` with `0b087c2a89`
under matching 2-second and 20-second controls on the same five sources. All 60
commands return and all 24 witnesses pass source checks. WordDesign's 20-second
overrun falls from 21.71–21.79 to 20.16–20.17 s; its reached nodes fall from 39 to
30. At identical 2000-node Eternity trajectories, process time rises from
2.98–3.08 to 3.40 s. Primitive visit accounting changes, so raw old/new work totals
are different currencies. [8052](https://lab.eignex.com/jobs/8052) isolates
per-operation polling metadata binding against `0b087c2a89`; its 30 ordinary runs
and twelve valid witnesses preserve trajectories with similar times, establishing
no separate throughput gain.

Retain immediate publication exit and retryable shared-assertion interruption.
[8061](https://lab.eignex.com/jobs/8061) compares `e36eb235ac` with `29ed910b19`
using two sequential conflictDriven CP arms, maxSD, LP off, 2-second and 20-second
deadlines, 2000 nodes, seed 3 and three repeats on Mac/aarch64. All 60 commands
return; all 22 reported witnesses pass independent checks, with matching sources,
runtime, options and non-solver dependencies. WordSquare's 20-second tail remains
20.87–20.97 s in both revisions. Eternity's 2001 reached-node control grows from
3.50–3.55 to 3.54–3.68 s; the 2-second candidate reaches fewer nodes. Table Nonogram
has different sliced paths and modest overhead; Regular preserves its work.
These controls establish neither a deadline-tail fix nor a throughput gain for
publication alone. Atomic hole-history and atom maintenance remain separate work.

Retain pending history/atom completion and retraction of interrupted decision
cascades. [8071](https://lab.eignex.com/jobs/8071) pairs merged parent `65796c1808`
with core `4337423bcf` using the same two-arm, 2-second/20-second controls. All 60
commands return, and all 18 reported witnesses pass source checks with matching
source hashes, runtime, options and non-solver dependencies. WordSquare remains
unknown with two nodes at 20 seconds: elapsed ranges are 21.02–21.15 s for the
parent and 20.58–21.19 s for the candidate, establishing no consistent tail gain.
Eternity reports 2001 nodes with different failure paths and 3.75–3.85 s instead
of 3.53–3.54 s. Table Nonogram reaches 814 instead of 922 nodes but takes
1.98–2.07 s instead of 1.86–1.90 s; both 2-second arms remain unknown. Regular
keeps similar timing with slightly different sliced paths. Native atom visits
also change the work charged to slices. These are bounded-execution and authority
changes with measured cost, not a throughput or hard-source recovery gain.

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

### Completed root refutations

Completed root refutations remain terminal through finite preparation; see
[presolve](../docs/07-presolve.md#rebuild-ownership).
[Lab 8105](https://lab.eignex.com/jobs/8105) compares `f59091a21a` with
`a2d2274258` on original `ChainReaction-20-020_c25`, CP/P1, LP off,
`default,+probe-int-holes`, seeds 3/17 and 20 s. Both complete 2636 probes and
report the same integer-hole refutation. Baseline then searches 11444/11674 nodes
and returns UNKNOWN; retained incremental and fresh-round controls return native
UNSAT before search in 3.73–3.99 s. The 10 s interrupted controls complete
1626/1502 probes and remain UNKNOWN. All eight cases share source SHA-256
`ae25fdbc0ebeb4d2a38ce455792f241208b32c83eb6deec5ae003ba221b22d93`,
one Linux/amd64 c7i.2xlarge spot node, runtime, dependency identities and JVM
options. These are native refutations without independent proof checking; the
single repeats establish handoff behavior, not general throughput.

### Source preparation reuse and polling

Reuse captured open plans only for the identical model and projection binding, and
retain bounded metered probe polling with exact visit charges and transactional
retraction; see [source preparation](../docs/07-presolve.md#source-routing-and-encoder-selection)
and [probing](../docs/07-presolve.md#symmetry-and-probing).

Certified source deletions can retain immutable built-in encoder rows while
rebuilding ownership. `SOURCE_SELECTION`, `SOURCE_ROWS` and `SOURCE_OWNERSHIP`
separate descriptor/mapping, row preparation and ownership/admission costs. These
observations are exclusive children of `SOURCE_ENCODER`: sum all four for a
complete replan, and compare whole preparation and CLI time too. Unchanged-plan
reuse stays in `SOURCE_ENCODER`; frontend routing captures initial planning.

`bench.profile.SourceEncoderReuseProfile` compares built-in and delegating custom
providers with presolve off or `off,+dependent-rows` on one hash-pinned linear SMT
satisfiability source.
Pass `suite=ID problem=NAME sha256=HEX repeats=3 warmups=1` through `lab profile`
with a 180-second job limit. It records initial setup, complete execution and
preparation costs, plus custom encoder selection/installation counts. Retained
source bytes and build provenance bind the observations; JFR timings are
instrumented, and native verdicts have no independent witness or proof check.

[8280](https://lab.eignex.com/jobs/8280), at `f6e9e0fb30` on Mac/aarch64, retains
all sixteen native SAT outcomes on `real-dependent`. After one warmup, three
measured custom-provider runs select six descriptors and install both retained
factors after deletion; unchanged executions select and install none. Built-in/custom
median preparation is 0.22/0.30 ms after deletion and 0.043/0.048 ms unchanged.
Whole setup plus execution is 1.79/1.66 ms and 1.69/1.68 ms respectively. These
instrumented small-source costs establish the fallback behavior, not a speedup.

[8254](https://lab.eignex.com/jobs/8254) compares `32cc4dfff6` with `1f33dff5c3`
on 24 original and control sources: seed 3, three repeats, P1, 10 s, debug enabled,
Linux/amd64, Serial GC and a 3 GiB heap. All 288 pairs match final feasibility,
objective and native proof flags, with identical source, runtime and non-solver
dependency identities. Dolmen accepts 144 witnesses; 48 checks are unknown and
288 unsupported because of type, objective, MPS syntax or decimal limitations.
Native optimality and UNSAT claims have no independent proof check.

On original fixnet-5000, median default preparation is 291 to 251 ms; isolated
equality preparation is 111 to 73 ms. Complete default replanning is 78 to 39 ms,
including about 8 ms selection, 25 ms row retargeting and 5 ms ownership. Unchanged
plan reuse stays below 1 ms. All original runs remain UNKNOWN at 10.16 s CLI wall
time. Default/isolated peak RSS changes from 315/314 to 317/309 MiB, and theory
checks from 880/889 to 863/907. These single-seed timings establish preparation
savings without a general solve-throughput or memory improvement.

[8305](https://lab.eignex.com/jobs/8305) compares baseline `cc39379df6`, retained
rows `76bfc96b96` and shared immutable metadata `f6e9e0fb30`: 648 cases on the
same 24 sources, seed 3, three repeats, P1, 10 s and debug enabled, on Mac/aarch64
with four concurrent cases, Serial GC and a 3 GiB heap. All 216 triples match final
feasibility, objectives, native proof flags and checker states. Source, runtime and
non-solver dependency identities match. Dolmen accepts 162 witnesses; 54 checks
are unknown and 324 unsupported, with the same checker limitations as above.

On fixnet-5000, baseline/rows/shared median exact preparation is 260/263/232 ms,
and complete replanning is 60/38/31 ms. Sharing metadata reduces retained row
preparation from 23 to 14 ms. With `exact=false`, complete replanning falls from
62 to 39 to 30 ms, but whole preparation rises from 278 to 284 to 304 ms as other
source-stage timings increase. Unchanged-plan reuse remains below 1 ms. All original
runs stay UNKNOWN at about 10.2 s CLI wall time; exact theory checks change from
1522 to 1531 to 1465. Small-source process medians change by -57 to +85 ms against
baseline, including unchanged controls, and preparation by -19 to +24 ms. These
parallel, single-seed observations support cheaper replanning, without uniform
preparation, startup, search-throughput or memory improvements; this run has no
external memory capture.

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

### Checked residual objectives

`bench.profile.MpsResidualObjective` replays saved MPS witnesses with all discrete
coordinates fixed and attempts a bounded continuous objective solve. Its `batch64=`
argument encodes a JSON array of objects containing `suite`, `problem`, `sha256`
and the saved `witness` line. It records the original and refined objective in
source units, the working objective scale, exact completion work, allocation and
a fresh `checkMpsWitness` result. Run it through a bounded lab profile job; native
source checks do not constitute independent witness or proof validation.
Optional `sourceGzip64` supplies gzip/base64 original MPS bytes from a retained
capture instead of catalog resolution. Decompression retains the same 16 MiB
source limit and mandatory `sha256` check; the report saves the uncompressed source.

Optional `repairSets` replays arrays of original integer column names through
`JointLinearRepair` instead of fixed-pin refinement. Each set frees precisely those
integers, pins the full discrete complement to the source-checked saved center and
keeps every source row and the full continuous residual. The special set
`"comparison-disagreement"` frees the integer differences from a separately
source-checked `comparisonWitness`; it is a diagnostic control, not an admitted
selector. No finite domain is added. `repairMillis`, `repairOperations` and
`repairWork` bound each cold attempt, defaulting to 1,000 ms, 256 LP operations
and 1 billion cumulative source LP work units. Requests admit at most 128 sets.
Reports retain exact complement pins, the strict minimized working cutoff,
conditioned bounds, pause/terminal reasons, checked candidate objectives, native
work, wall time and thread allocation separately. A conditioned closure can retain
a feasible nonimproving point and never establishes a source refutation or source
optimum. The capture is instrumented and makes no whole-CLI speedup claim.
An entry `{"count":2,"round":1}` instead captures the existing mixed selector's
freed set with its actual source LP guidance before replaying it at the saved
center. Root guidance/index/selection work is recorded separately in
`selection.json` and is excluded from each cold repair allowance; these captures
compare repair capability at fixed freedoms, not total selector throughput.
Original real columns with proved integer backings remain free in every replay
residual. The selection capture distinguishes those working integer coordinates
from the effective original integer freedoms; they never become complement pins
solely because lowering gave them integer storage.

[Scoped baseline 8448](https://lab.eignex.com/jobs/8448) runs `e805182280` on
Linux/amd64 with the two hash-matched original modglob/rgn sources and refined
centers from 8339. Runtime and dependency hashes, debug settings and 3 GiB Serial-GC
options are retained. Each cold repair has five seconds, 256 LP operations and
1 billion cumulative native LP work units; root guidance and selection are separate.
All 20 attempts complete their capture and retain a native original-source-checked
point. No independent witness or proof check is claimed.

Modglob has 98 declared integers and two original real columns with proved integer
backings. The existing first four pair selections spend one coordinate on a
backing, leaving one original integer freedom per replay. With the full real
residual free, x223 and x255 each admit a strict improvement from 35.15M; x255
reaches about 33.52M in three LP checks, 20,132,800 funded work and 58,331,844
modeled allocation bytes. Its instrumented wall time is 0.60 seconds and thread
allocation 224,641,008 bytes. The other two single-input controls close only their
conditioned cutoffs. Every 30-input broad set and the 55-input finite-disagreement
control reaches its five-second limit without improvement; their incomplete
searches establish no neighborhood exhaustion. Broad attempts allocate 5.29–9.79 GB
cumulatively on the measured thread, distinct from modeled retained allocation or
peak RSS. Larger freedom counts supply no quality or throughput advantage here.

Rgn retains its 82.2 center throughout. The fixed control and three pair controls
close their conditioned cutoffs; the remaining pair, four 32-input controls and
four-input disagreement control return unavailable completion with unresolved
alternatives. These are neither local infeasibility nor source exhaustion.
The capture selects source-role-preserving proposal diagnosis, without changing
production admission, proving a basin, or replacing the stronger 20.78M finite
modglob controls. Nominal conditioned bounds establish no tolerance-source optimum.

[Fixed-pin replay 8339](https://lab.eignex.com/jobs/8339), at `b738c5d89a` on
Mac/aarch64, retains four witnesses from [8302](https://lab.eignex.com/jobs/8302).
With unchanged discrete pins, the residual modglob point improves from
312,428,931.7216991 to 35,147,088.883410625 and rgn from 97.59999892 to 82.19999924.
Modglob's exact nominal fixed-pin bound equals its refined objective; its remaining
gap to the 20.78–21.11M finite controls requires different pins in that nominal
system. The ordinary finite witnesses have only tiny rational-rounding improvements.
Discrete objective terms alone do not measure pin quality: pins constrain continuous
costs too. The replay's nominal bounds establish no optimum under declared source
tolerance, and refinement consumes only checked primal coordinates.

The residual modglob refinement uses 2,951,676 floating work units and 203 pivots,
plus 858,825 reconstruction/basis work units; rgn uses 108,019 and 35, plus 54,741.
Their metered exact recovery allocations are about 17.9 MB and 3.1 MB. Native
original-source checks accept all four refined points. Independent solchecker
cannot consume modglob's nonterminating rational output; that is an unsupported
validation route, not an independent witness check. These captures measure the
mechanism and do not establish production throughput.

[Qualification 8340](https://lab.eignex.com/jobs/8340) compares `51f1828a66` with
`b738c5d89a`: five original sources, ordinary defaults and numerical companions,
default/exact policies, P1, 30 seconds, seeds 1/2/3 and default presolve. All 120
cases report feasible points, 111 pass independent solchecker and nine refined
modglob witnesses are unsupported nonterminating rationals. All 48 native
gr4x6/p0033 optimum labels agree; native proofs are not independently checked.
Source hashes, runtime/options and non-solver dependency JARs match on Mac/aarch64;
compiler identity is unreported.

All nine numerical modglob pairs improve from 312.43M to 35.15M, while ordinary
default-mode finite search retains roughly 20.78M points. Both rgn companion
policies improve from 98.19999924 to 82.19999924 in all three seeds, matching the
strong default-mode control. Rgn bound closures increase from four to 55, pending
leaves fall from 812 to 685 and numerical funded work from 375.12M to 364.06M.
Bell3a companion objectives agree. Ordinary bell3a has one quality gain and one
loss without refinement activity. Ordinary exact rgn loses at two seeds and ties
at the third: 141.8/121/116 become 153.6/156.2/116, approximately.

Whole-CLI external-time measurements collect no physical allocation estimate.
These are medians across seeds, not independent timing repeats:

| Companion source and policy | Peak RSS KiB, base to refinement | Wall seconds, base to refinement |
| --- | ---: | ---: |
| modglob default | 502528 to 490176 | 30.10 to 30.10 |
| modglob exact | 470016 to 527136 | 30.10 to 30.10 |
| rgn default | 419408 to 428192 | 18.77 to 16.17 |
| rgn exact | 417392 to 409504 | 18.47 to 15.95 |

Modglob's modeled joint allocation increases from 10.04 to 29.78 MB and its first
point arrives about 90 ms later. Neutral bell3a attempts increase modeled joint
allocation from 34.89 to 111.14 MB under the default policy. P0033 companion wall
medians change from 3.60/4.36 to 4.50/4.23 seconds despite unchanged LP counters
and no refinement activity. These results establish no general throughput gain.

[Ordinary exact rgn repeats 8343](https://lab.eignex.com/jobs/8343) compare the same
base with `36d933a39e` on Linux/amd64: three fresh repetitions of seeds 1/2/3,
18 cases at 30 seconds. All witnesses pass solchecker. Treatment has two wins,
three losses and four ties; median objective changes from about 131 to 140.2.
Both versions vary within a seed. This separate platform does not remove the Mac
regressions or establish an ordinary exact-mode quality improvement.

Matched neutral-point replays [8344](https://lab.eignex.com/jobs/8344) and
[8345](https://lab.eignex.com/jobs/8345), on Mac/aarch64, retain both exact source
points, 203/35 pivots and the same basis fill/width. Comparing objective cost before
checking a neutral proposal reduces helper work from 33739 to 17872 on modglob
and 14783 to 11170 on rgn; modeled helper allocation changes from 1256355 to
744782 bytes and 590938 to 456836 bytes. Residual assembly remains paid. Improving
proposals still require complete strict-row and original-source checks.

[Attained-bound qualification 8346](https://lab.eignex.com/jobs/8346), on
Linux/amd64, compares the base with `a3401d7c05` across 12 gr4x6 cases. Skipping
refinement at a checked attained bound retains all checked 202.35 points and
native proofs, 45 checks and 65 nodes. It restores the base's 1942663 floating and
2786191 funded work units, avoiding the nine extra pivots observed in 8340.
The remaining modglob boundary is [discrete proposal quality](https://github.com/Eignex/klause/issues/3610),
not larger continuous admission. Ordinary exact-mode trajectory losses remain a
qualification limit of this bounded point-refinement mechanism.

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

### Incremental extensional local search

Ground Table local search shares bounded relation postings and retains row-distance
buckets; MDD local search retains its forward/backward layers and stops accepted
updates when they stabilize beyond the edited positions. See
[local-search projections](../docs/10-local-search.md#projections-and-preparation).

[8112](https://lab.eignex.com/jobs/8112) compares `ee56127636` with `e5970b653f`
on six original XCSP3 sources, LS and mixed/P1, seeds 1/3 three times and 15 seconds.
All 144 cases run on one Linux/amd64 spot node with matching source, runtime,
options and non-solver dependency hashes. All 90 returned witnesses pass independent
XCSP3 checking. WordSquare `tab2-07` gains six checked witnesses, from 6/12 to 12/12;
the other paired feasibility outcomes are unchanged.

On WordDesign2 `mdd-005`, median LS process time falls from 5.723 to 3.836 seconds
with the same five/twenty moves, and median peak RSS from 1,366,346 to 840,708 KiB.
WordDesign1 `words-005` regresses from 2.096 to 2.798 seconds with the same
1455/1462 moves; its RSS falls from 172,194 to 163,326 KiB. Crossword has mixed
seed costs. These whole-process observations establish no uniform move-rate gain;
[8139](https://lab.eignex.com/jobs/8139) separates fixed-work cost and preparation.

Large WordSquare changes twelve 50-second hard kills into twelve unknown returns
at 28.86–29.99 seconds with 2050 moves; complete mixed arms still receive no segment.
Large WordDesign2 retains nine hard kills in twelve candidate cases. Missing RSS
and counters from killed processes are unavailable measurements. These deadline
failures motivate [bounded-operation comparison](https://lab.eignex.com/jobs/8133),
with [Mac JFR attribution](https://lab.eignex.com/jobs/8146) kept separate from
ordinary cost and checked outcomes. RSS is external process peak memory, not
allocated bytes, and witness checking supplies no independent native proof.

### Bounded extensional local-search operations

Accepted Table/MDD updates retain a private cursor between fixed-size work batches;
read-only candidate scans can be discarded, while compound probes restore completed
primitive edits before yielding. Satisfaction polling adapts to elapsed work. See
[local-search lifecycle](../docs/10-local-search.md) and
[neighborhood evaluation](../docs/11-neighborhoods.md).

[8133](https://lab.eignex.com/jobs/8133) compares incremental `e5970b653f` with
bounded `253e3e30ec` on the same six sources, LS/mixed/P1/15s, seeds 1/3 three
times. All 144 ordinary cases run on one Mac/aarch64 host; all 96 returned
witnesses pass independent XCSP3 checking, with unchanged paired feasibility.
The 24 bounded large-source cases return in 15.115–15.264 seconds. Incremental
MDD015 cases hit the 50-second kill limit; large WordSquare takes 17.28–17.30
seconds at the median. Bounded mixed runs give complete workers 16–33 segments
on MDD015 and 29–35 on large WordSquare. Maximum LS segments still reach 3.161
seconds, including preparation, so this supplies cooperative progress rather than
a strict per-operation wall-clock bound. All six bounded LS-only MDD015 runs
report an isolated worker heap failure during initialization under the 3 GiB cap.

WordDesign1 median LS elapsed falls from 2.305 to 1.952 seconds, Crossword rises
from 0.629 to 0.641 seconds, and WordSquare `tab2-07` rises from 3.307 to 4.213
seconds. Polling changes worker scheduling and move counts; these are default-route
observations, not equal-work speedups. Default-route median RSS rises, including
large WordSquare mixed from 216,688 to 1,244,736 KiB as complete workers receive
time, and small MDD LS from 819,208 to 843,760 KiB.

[8139](https://lab.eignex.com/jobs/8139) compares parent, incremental and bounded
revisions with one fixed CBLS arm, presolve off, node limit 160 (128 LS moves),
phase clocks and a 60-second safety deadline. All 108 cases complete; all 27
returned witnesses pass independent checking. Five source groups run six repeats
per revision on the Mac; Crossword runs one matched triple there and five on a
Linux/amd64 spot node. Each triple retains its host, work and witness hash; platform
costs are not pooled. Early solutions use five/twenty moves on small MDD and 52
on Crossword seed 3 rather than the full allowance.

On the Mac, large MDD median elapsed is 38.527/24.226/33.299 seconds for
parent/incremental/bounded, with peak RSS 3,013,976/1,230,464/1,332,032 KiB.
Incremental/bounded selection clocks are 16.153/19.284 seconds and application
6.513/9.719 seconds. Large WordSquare elapsed is 2.220/1.077/1.404 seconds,
whereas WordDesign1 is 0.380/0.481/0.544 seconds and RSS
135,080/177,272/202,976 KiB. These short process measurements include preparation
and JVM compilation; they establish neither steady-state throughput nor a uniform
memory improvement. Retain bounded operations for deadline and worker progress,
with explicit polling, cursor and relation-index costs. Mac
[JFR 8146](https://lab.eignex.com/jobs/8146), summarized by
[8150](https://lab.eignex.com/jobs/8150), identifies residual MDD layer maintenance,
including reversible scoring probes; its instrumented timings are not throughput
evidence.

### MDD changed-state frontier admission

Retain changed-layer sweeps rather than the shared-index, worker-local state-frontier
prototype. [8167](https://lab.eignex.com/jobs/8167) compares bounded `253e3e30ec`
with frontier `a73c361a66` under a 32 MiB adjacency admission estimate and a separate
32 MiB symbol-posting budget. All 216 cases run on one Linux/amd64 spot node;
all 102 returned witnesses pass independent checking. Across six sources, default
LS/mixed and fixed-work controls, there are two paired feasibility gains and four
losses. Small-MDD fixed-work median elapsed rises from 4.433 to 4.929 seconds,
with nearly unchanged peak RSS (904,000/903,600 KiB); mixed feasibility falls from
6/6 to 3/6. Large-MDD frontier fixed-work cases reach only 90–118 moves at the
60-second safety deadline; controls finish 128 moves at a 56.357-second median.

[8172](https://lab.eignex.com/jobs/8172) isolates a corrected prototype `035b54312d`
that preserves direct primitive probe loops outside index admission. Two MDD
sources run on two spot nodes, with matched source/runtime/host pairs and a
90-second fixed-work safety deadline. All 72 cases complete and all 36 returned
witnesses pass independent checking. All fixed-work pairs retain move counts and
witnesses, and paired feasibility is unchanged. Large-MDD 128-move medians are
56.05/55.31 seconds on one node (two repeats) and 59.31/60.33 on the other (four).
Small-MDD fixed-work medians are 2.779/2.873 and 6.348/6.490 seconds (three repeats
per node); fixed-work peak-RSS medians differ by under 0.2% within each source/host
group. Restoring the direct fallback removes the introduced probe overhead
without a consistent additional gain. These controls do not justify retaining
the extra index and frontier policy under the measured admission budget.

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

### Source repair distance and exact freedoms

[Capture 8427](https://lab.eignex.com/jobs/8427), at `30c3d4b7f7`, runs the three
self-authored finite repair controls on Linux/amd64 with seed 7 and 128 counted
moves per walk. Objective-coordinate and existing structured descent each have
an unobserved and observed repetition. Two additional assignment captures use
instruction slices; their phase records match the unsliced records byte for byte.
All eight final-witness/objective/pick/work/restart/stall comparisons pass.
Original source hashes, runtime, dependency and solver/CLI identities
remain in the capture. All 196 bounded CP completions pass native original-source
checks; those checks establish no independently checked proof or unpinned optimum.

For each relation, complete two-coordinate freedoms admit strict improvements at
three of four coordinate-descent endpoints. Eight individual freedoms admit none;
sixteen size-matched samples find two improvements. Existing structured descent
reaches objective zero on every source, and complete blocks admit no improvement
at its three recorded feasible endpoints. This separates a missed coordinated
completion from exhaustion at a stronger endpoint. It supplies no reason to replace
the existing structured sources or admit a removal policy into default routing.

Assignment and resource blocks spend one CP node per completion; placement blocks
spend two. Individual controls spend zero or one. These units remain separate from
physical time. The trace retains at most 32 attempts, with 942/1,256 modeled
coordinate reads on the coordinate-descent controls and 102/136 on structured
controls; 97 further coordinate-descent attempts decline capture. Censored records
receive no basin label. Per-walk thread allocations and elapsed time are retained,
but the cold ordered instrumented repetitions establish no overhead or speedup claim.

[Source checks 8434](https://lab.eignex.com/jobs/8434), at `272a5fea2a`, retain
all three original hashes and run CP, mixed and structured LS with seeds 7/19,
P1, two seconds and presolve disabled. All 18 final witnesses have objective zero
and pass XCSP3-tools SolutionChecker 2.5.1 at `ca58fc79a4`. Native proof claims
remain separate from those independent witness checks. The small controls select
the observation contract; larger coordinated barriers require further comparisons.

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

### Retained source query funding and rebinding

[8231](https://lab.eignex.com/jobs/8231) and
[8233](https://lab.eignex.com/jobs/8233) compare retained source sessions at
`706e08e7d3` and `7085c1e25f` on four hash-pinned original roots, with a 600M
caller work ledger and 20M slice grants. The candidate retains one preparation and
one operation per source. On the compact equality layout,
[8258](https://lab.eignex.com/jobs/8258), integrated on main `5288698ae6` at
`80a3ac0803`, reaches misc04inf's
numerical candidate at 267,223,843 floating work and 2,222 pivots. Its 548,163-work
bound check declines the candidate; this establishes no bound or source verdict.
Rentacar advances to 1,568 pivots and 598,088,504 floating work before the finite
caller ledger exhausts at 600,342,003 total work, including atomic overspend.
It supplies no candidate. Preparation allocation remains 5.8/16.4 MB respectively.
Cap6000 and p0033 retain completed root guidance. These captures contain no
independently checked source witnesses or end-to-end throughput claim.

The identical 32-scope dcmulti binding streams
[8232](https://lab.eignex.com/jobs/8232) and
[8234](https://lab.eignex.com/jobs/8234) use those same base/candidate revisions,
JDK 25.0.4.1 and Mac/aarch64. All 641 assertions per scope preserve their checked
witness order, side, depth and common digest. Both retain one source owner and
identical edit counts, without constructing numerical factors. Excluding the first
four transitions, mean measured thread
allocation per bind is 25.70/0.58 MB and mean instrumented time is 10.09/1.26 ms.
This is a scoped binding cost observation; JIT, JFR and a single source limit the
timing interpretation. Source-checked cold CLI controls measure the consuming solve
separately.

Cold CLI [8257](https://lab.eignex.com/jobs/8257) compares `cea86e1cd4` and
`80a3ac0803` on those four roots plus dcmulti, at P1/exact/10 seconds, presolve
off, debug off, seeds 3/5 and three repeats. All 120 source hashes match their
frozen identities. Runtime and external dependency bytes match on Mac/aarch64, with a
3 GiB heap, Serial GC and external whole-CLI resource measurement. All 48 reported
witnesses pass independent solchecker; native proof claims are not independently
proof-checked.

The fixed numerical p0033 consumer retains one LP preparation over 6,907 queries
and 6,906 reuses in every run. Both revisions spend 31,364,243 floating work and
report native optimality at 3089. Mean physical CLI wall seconds are 5.56/4.77,
user CPU seconds 11.46/9.90 and peak RSS 367/345 MiB. These six paired runs over
two seeds are a bounded cost observation, not a general throughput claim.
The finite LP controls preserve all outcomes and objectives: dcmulti 951693/5,
cap6000 -99670 and p0033 3089, with p0033 optimal; rentacar and misc04inf supply
no incumbent. Finite p0033 remains faster at 3.19/3.20 seconds. The other fixed
numerical sources decline before retained LP preparation and supply no batching
or sparse-lifetime cost evidence. These results support the checked binding
infrastructure without changing default worker admission.

### Bounded numerical companions in finite MPS portfolios

[Qualification 8332](https://lab.eignex.com/jobs/8332) compares automatic admission
at `c19fef3db2` with unmodified main `02fd784969`, including retained integer
probing and complete element LP groups. It retains all 20 source identities,
seeds 1/2/3, 10/30-second budgets, P1, default presolve and declared source
semantics. All 360 commands complete on Mac/aarch64 with debug off, a 3 GiB heap
and Serial GC. Source hashes, runtime/dependency hashes and runtime options match
across arms; records retain revision and installed-build fingerprints.
Explicit compiler identity is unavailable in these records.

Each budget has 60 cases per mode. All 274 reported feasible witnesses pass
independent solchecker, with no worker failures or faults. Native completion
includes refutations; native proof claims are not independently proof-checked.

| Mode | Feasible 10s / 30s | Native complete 10s / 30s |
|---|---|---|
| Unmodified finite default | 45 / 48 | 19 / 19 |
| Automatic finite companions | 48 / 48 | 24 / 24 |
| Numerical-first companions | 41 / 44 | 21 / 21 |

Nine sources admit numerical workers. All six cap91 candidate runs return checked
points at 796648.4375 with native optimality. The finite control has no short-budget
point and worse unproved long-budget objectives. Short-budget repair consumes
1.17/1.28/1.29 seconds of active time; completed-point publication takes
253/290/286 ms under the containing lifetime. Enigma retains all six ordinary
finite proofs, while numerical-first returns four checked points without proofs.

Objective quality remains mixed: pp08aCUTS has five losses and one win, dcmulti
three losses, two wins and one tie, and modglob four wins and two losses over the
six paired runs. Deadline variation also occurs in models outside companion
admission. Rgn seed 1 at 30 seconds records 174 certified scoped numerical bounds,
140 child-bound gains, 19 LP conflicts and 10 coordinate conflicts in 4.20 seconds
of active work, including atomic overspend, without numerical completion.
These counters describe bounded progress separately from source outcomes.

[Serial qualification 8333](https://lab.eignex.com/jobs/8333) compares the same
revisions on gr4x6, cap91, p0033 and enigma, with three repeats per seed at
10 seconds. Whole-CLI external-time measurements use the same Mac/aarch64 host,
heap, GC and runtime options, without JFR; allocation is not collected.
Measurement manifests bind raw solve records by SHA-256, source/build identity,
seed, budget, processors, witness, proof flag and case index.
All 216 commands complete without worker failures or faults: 196 witnesses are
independently valid, nine checks are unsupported and eleven runs return no point.
The candidate modes report native optima in all 72 trials, each with an
independently valid witness. CPU below sums user and system time.

All 54 gr4x6 witnesses are independently valid. The exact/presolve-off comparison
has nine native proofs at 4047/20 per mode; these are descriptive medians over
three seeds and three repeats.

| gr4x6 exact, presolve off | Wall s | CPU s | Peak RSS KiB |
|---|---|---|---|
| Finite CP, one LP worker | 1.15 | 2.67 | 243936 |
| Automatic finite companions | 0.59 | 1.18 | 207120 |
| Numerical-first companions | 0.58 | 1.16 | 207008 |

Declared automatic mode also proves all nine trials, with median wall/CPU/RSS
0.59s/1.21s/209136KiB. The unmodified finite default proves eight of nine; its
unproved trial remains in the outcome record.

All 18 cap91 candidate trials, declared and exact/presolve-off, return independently
valid points at 796648.4375 with native optimality. Keeping the finite portfolio
has a cost: declared candidate wall/CPU/RSS medians are
6.01s/12.07s/476928KiB versus numerical-first 3.00s/6.02s/310176KiB, with nine
equivalent native optima each. The finite default returns nine worse unproved
points. All nine finite-LP exact-control witnesses remain checker-unsupported:
289/1092 has no exact decimal representation for solchecker's decimal input.
They are retained without approximation and excluded from independently checked
outcome claims.

P0033 and enigma retain all nine declared-mode native optima per revision, at
3089 and 0 respectively. Their finite-default/candidate costs remain close;
medians are wall seconds / CPU seconds / peak RSS KiB.

| Source | Finite default | Automatic finite companions |
|---|---|---|
| p0033 | 0.41 / 0.77 / 157856 | 0.41 / 0.79 / 161712 |
| enigma | 1.37 / 3.04 / 242896 | 1.36 / 3.00 / 243120 |

Numerical-first proves all nine declared p0033 trials at a 3.86-second wall
median. Its declared/exact enigma modes return four/three checked points and
no proofs, reaching the 10-second limit. These outcome and cost differences
bound the admission claim to the retained finite recipes and measured sources.

### Linear traversal comparison

Automatic MPS routing keeps the ordinary finite portfolio and appends bounded
numerical workers to eligible binary/continuous models.
`param.linear-traversal=finite-companions` explicitly selects that policy.
Use a pinned unmodified control revision for admission comparisons, retaining
presolve and full finite recipes.
`finiteNumericalCompanionActiveMs`/`finiteJointCompanionActiveMs`,
`Retired`, `Optimal` and `Refuted` suffixes expose each trial's active cost and
completion. The numerical-first `numerical-companions` control records
`finiteCompanionNativeOptimal`, `finiteCompanionNativeRefuted` and
`finiteCompanionNativeExhausted` before withholding finite proof authority.
These stage fields distinguish worker completion from transport; inspect
`armFailure.*` and per-arm failures and faults alongside source outcomes.

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
source matrix preparations from compatible scoped consultations. Record their checked
bound/incumbent trajectories and replay work alongside memory and wall time.
`numericalLpPauses` counts source operation suspensions; sparse phase fields count
consultations ending in preparation, floating solve, certification or completion.
Paused consultations publish neither coordinates nor a bound. Source-LP operation
counts advance on changed authority, while work fields contain only new work from
each continuation.
`numericalCoordinateConflicts` counts source-checked contradictions between opposed
coordinate sides; these closures spend no floating solve operation.
`numericalInheritedBoundClosures` counts queued children closed by an ancestor's
bound after checking its derivation in the child scope against the current
incumbent; these closures spend no LP query.

`param.linear-traversal=numerical-companions` schedules the frontier, an admitted
finite search and joint repair together. Compare it with
`numerical-companions-independent`, which withholds cross-worker incumbent and
bound imports under the same worker quotas, and with standalone finite, frontier
and repair controls. Keep the same total deadline, decision/move and source LP
work/allocation ceilings. `param.source-lp-operations=256` retains a fixed-count
control; `param.source-lp-work-limit` and `param.source-lp-allocation-limit` set
cumulative numerical funding. Native objective units and original-source objective
units must remain separately recorded. Companion exhaustion grants no global proof.

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
`numericalLpConflicts` counts source-checked LP refutations; coordinate-side
contradictions have their separate `numericalCoordinateConflicts` counter.
`smtSourceLpConflictCertificationWork` reports metered Farkas reconstruction,
including refused candidates, separately from bound certification. Completed
consultations reuse certificates without repeating their work charges.
`numericalIncumbentDominatedSplits` counts proposed fractional splits whose checked
bound already dominates the invocation's checked incumbent.
`numericalIncumbentBoundClosures` counts unexpanded subtrees closed with those
scoped bounds, including nodes without a fractional proposal. Their derivations
remain in complete sibling coverage; the final bound and original-source witness
are rechecked together before an optimum can be reported.
`nativeNominalObjectiveBound` is the checked native bound in original objective
units; it does not certify the feasible set enlarged by a positive tolerance.
Independent source witness validation remains separate from solver proof claims.

`param.source-lp-work-limit` and `param.source-lp-allocation-limit` fund retained
numerical traversal and joint repair cumulatively. Grants preserve their spent
work, sparse preparation and retained-coverage allocation. The independent
`param.source-lp-operations=256` ceiling supplies a fixed-count control.

The completed bell3a pairs in [8131](https://lab.eignex.com/jobs/8131) compare those
policies on the same pre-cut revision `2013ac4ec4`, original source hash, runtime,
dependencies and Linux/amd64 c7i.2xlarge spot host. They use exact semantics, P1,
ten seconds, presolve off, debug capture, seeds 1/2/3, one billion numerical work
units and 512 MiB of cumulative sparse/coverage allocation. The frontier expands
from 399 checked nodes and 57 checked LP conflicts to 2449–2600 nodes and 453–485
conflicts. Closed leaves rise from 119 to 680–728; pending alternatives also rise
from 81 to 1088–1143 as the tree grows. No frontier witness or terminal appears.

Funded work rises from 58.1 million to 354.5–374.1 million units. Median physical
whole-CLI wall time rises from 2.20 to 10.15 seconds and peak RSS from 196,808 to
240,036 KiB. Joint LS and ALNS also continue beyond 256 operations when that ceiling
binds. These pairs support retained useful work at higher cost, with unresolved
coverage still present; they establish no general speedup or independently checked
proof. The wider corpus and standalone/companion controls remain separate evidence.

`bench.profile.AffineObjectiveProfile` takes `batch64=` containing a base64-encoded
JSON list of `suite=ID`, `problem=NAME`, `sha256=HEX` argument lists. It retains the
original MPS bytes, original translation rows and complete prepared factor keys,
rows, integer/Boolean readers, domains and objectives. Optional `presolve=CONFIG`
selects the same finite pass controls as ordinary solves, including symmetry and
affine counterfactuals. Default and exact source policies each compare
objective substitution enabled and disabled through the full finite preparation.
Preparation work, elapsed time and interruption are diagnostic observations;
source-matched ordinary lab solves and witness checks qualify search outcomes.
The `scan=SUITE` form inspects original MPS integer translations across a complete
catalog suite under a two-minute deadline, retaining source hashes, objective reads
and candidate bytes without preparing or solving the models.

[8134](https://lab.eignex.com/jobs/8134), at `678caea38f` on Mac/aarch64, finds no
original unit translations in flugpl, gr4x6, opt1217, p0033 or p0201. All ten
default/exact enabled/disabled residual pairs are byte-identical, with no objective
change. Default preparation work agrees; exact p0033 and p0201 spend another 192
and 2,412 units on objective analysis. Fixed-order instrumented timings establish
no speedup. The complete 65-source MIPLIB 3 scan
[8140](https://lab.eignex.com/jobs/8140) retains 33 cap6000 and 40 fiber alias rows
whose two integer columns both have nonzero source objective weights; all offsets
are zero. Candidate shapes alone establish neither retained contraction nor solve benefit.

The full residual capture [8154](https://lab.eignex.com/jobs/8154), at `f9e35299f2`
on Mac/aarch64, finishes all eight preparations without interruption or refutation.
Default-tolerance enabled/disabled residuals are byte-identical. Exact cap6000
contracts 33 objective terms and 33 factors (5,995 to 5,962 terms; 2,171 to 2,138
factors), with preparation work rising from 52,623,382 to 58,214,539. Exact fiber
contracts 51 objective terms and 58 factors (1,254 to 1,203 terms; 364 to 306
factors), with work falling from 34,368,838 to 32,246,299. Objective constants stay
zero. Fixed-order instrumented preparation takes 110.5 versus 423.2 ms on cap6000
and 149.7 versus 133.4 ms on fiber; these observations qualify cost rather than
establishing a speedup. Original MPS bytes, hashes and complete residuals are
retained with the job.

The ordinary solve comparison [8145](https://lab.eignex.com/jobs/8145) compares
`0fffa679f2` with `f9e35299f2` on one Linux/amd64 c7i.2xlarge spot worker: the
eight controls plus cap6000 and fiber, default/exact semantics, P1, one backtrack
arm, debug on, a 10-second allowance and seed 3 repeated three times. All 120 runs finish;
all 96 feasible witnesses pass the original-source solchecker. Source hashes,
runtime, non-solver dependencies and JVM options match. MPS has no external
compiler identity. Native optimum/refutation claims agree on all completed
controls; the lab checks witnesses, without an independent proof certificate.
Default arms retain the same incumbent, proof status and preparation work.

Exact fiber improves its incumbent from 1,458,797.07 to 1,029,093.08 in all three
repeats, with preparation work falling from 34,398,629 to 32,276,090. Exact
cap6000 retains incumbent -2,442,801; two translated runs interrupt affine
preparation under the ordinary allowance, retaining 28 and 21 contractions,
and the third retains all 33. Its smaller charged work therefore does not mean
cheaper complete preparation. Median exact observations show the tradeoff:

| Source | Protected preparation (ms) | Translated preparation (ms) | Protected nodes | Translated nodes |
| --- | ---: | ---: | ---: | ---: |
| cap6000 | 1,080 | 1,705 | 35 | 31 |
| fiber | 1,270 | 1,141 | 26,230 | 18,869 |

Neither source proves optimality within the allowance. The remaining controls
retain their objective and verdict; completed searches retain their node counts.
This qualifies exact finite objective substitution with a fiber quality gain and
a cap6000 preparation cost, without a general speedup claim.

Definition-specific objective admission in [8213](https://lab.eignex.com/jobs/8213)
compares `32cc4dfff6` with `14584471e9` on Mac/aarch64, P1, ten seconds and seed 3
repeated three times. All 54 witnesses pass original-source checking. Exact
cap6000 retains its incumbent while median preparation falls 596 to 420 ms and
finite affine analysis 280 to 95 ms; charged analysis falls 133,902 to 130,312
units with unchanged rebuild work. Exact fiber improves 1,292,011.56 to
1,107,576.76. Declared outcomes and gr4x6 native proofs agree. Costs remain:
median exact cap6000 peak RSS rises 480,592 to 520,480 KiB and fiber CPU time
17.94 to 18.83 seconds. These isolated-base observations establish no general speedup.

Current-main residual captures [8271](https://lab.eignex.com/jobs/8271) and
[8272](https://lab.eignex.com/jobs/8272) compare `51e0324eba` with `b751b6c55e`
on the same original cap6000/fiber hashes. Both declared-fiber generators move
only columns 1264/1266/1267, whose only row readers are the two symmetry orders.
The active mask removes that generator factor and both orders, retaining all
other factors, domains and objective; the residual matches symmetry disabled.
All 24 exact/declared preparation controls complete without cancellation. These
captures inspect preparation and make no search, source-witness or proof claim.

[Current-default integration 8270](https://lab.eignex.com/jobs/8270) compares
pre-admission `dccd34fe6d`, admission `b751b6c55e` and active-mask `51e0324eba`,
with affine/symmetry-disabled controls, P1, ten seconds, seed 3 and three repeats.
Fiber has two Mac/aarch64 repeats and one Linux/amd64 repeat; cap6000, gr4x6 and
CoinsGrid run on Linux. Comparisons stay within actual hosts, with matching source
hashes, runtime, non-solver dependencies and JVM options. Completed witnesses pass
independent source checks. Gr4x6 and CoinsGrid retain native optima 202.35 and 200;
witness acceptance does not independently certify those proofs.

Admission retains cap6000's -2,442,801 incumbent while exact preparation falls
from 913–942 to 668–688 ms in completed Linux comparisons. Exact fiber improves
from 1,292,011.56 to 1,107,576.76 on Mac, but regresses from 1,029,093.08 to
1,109,204.76 on Linux. The active mask retains those exact-policy incumbents.
Declared fiber improves from 1,175,639.51 to 1,147,376.57 on Mac and from
1,458,797.07 to 1,205,594.73 on Linux, matching the symmetry-disabled controls.
Its preparation falls from 331–337 to 126–131 ms on Mac and 707 to 245 ms on
Linux; visited nodes rise from about 40,900 to 161,000 and 19,781 to 95,630.
CoinsGrid's removed generators also move only unread columns, so it is an
objective/proof control rather than evidence of retained live symmetry.

The bounded preparation benefit coexists with search and resource costs. Linux
exact fiber visits fewer nodes after admission (28,258 to 18,845) but performs
more LP solves (843 to 1,042) and retains the worse incumbent. Adding the active
mask raises that repeat's peak RSS from 318,672 to 334,700 KiB; whole-CLI wall
time stays 10.17–10.18 seconds. These results establish neither a uniform objective
gain nor a general runtime or memory improvement.

The integrated companion [qualification 8173](https://lab.eignex.com/jobs/8173)
pins `6fb51fd8fd`: 450 runs over the fifteen frozen 8087 sources, three seeds,
exact and declared `1e-7` absolute/relative tolerance policies, P1, ten seconds,
presolve off and a containing 100-million-unit decision/move allowance. Automatic
CP/`lp-default`, forced frontier and joint-repair controls accompany sharing and
independent companions. Numerical work/allocation ceilings are partitioned across
private owners. Runs span Mac/aarch64 and two Linux/amd64 c7i.2xlarge spot nodes;
each source/seed comparison has matching host, runtime, dependencies and options.
All source hashes match 8087, and all 211 reported witnesses pass solchecker.

Sharing improves nine objectives and adds two feasible outcomes in 90 matched
pairs, with 79 identical feasibility/objective/native-proof outcomes and no paired
loss. The gains occur on dcmulti, pp08a, bell5 and enigma. There are 77 numerical
incumbent adoptions and 468 pooled repair-centering events; the independent recipe
records none. Exact bell3a closes 11–14 alternatives with imported incumbents,
versus zero, retaining the same 878430.316 point. Its median Mac whole-CLI wall
time stays 10.10 seconds; median peak RSS is 379,744 versus 393,856 KiB.
These observations establish working exchange and scoped pruning, not equal actual
work or a general speedup. The corpus imports no finite objective floor: that
channel accepts only ascending unit integer objectives without offsets, with
compatible import and incompatible-unit refusal covered by deterministic tests.

The p0033 [terminal replay comparison 8178](https://lab.eignex.com/jobs/8178)
compares `6fb51fd8fd` with `8a0d14ff46` on Mac/aarch64, exact/P1/10s/presolve off,
sharing and independent recipes, seed 2 repeated three times. Source, runtime,
non-solver dependencies and options match; all twelve witnesses pass solchecker.
Direct typed-terminal replay publishes all six native optima at 3089, versus none,
with the same 1,995 numerical checks and 27,593,443 funded work units. The standard
verifier step/depth limits remain unchanged. Median sharing whole-CLI wall time
falls from 10.11 to 4.61 seconds and peak RSS from 375,280 to 307,488 KiB by ending
the portfolio at its checked completion. Repeats are not independent seeds, and
standalone frontier remains cheaper on this source. Native proof claims remain
separate from independent witness checks; default admission remains unchanged.

The scalar session [qualification 8040](https://lab.eignex.com/jobs/8040) compares
cold `3e8ac384` with retained `6aef3fdd` and an automatic finite control over
opt1217, p0201, gr4x6, flugpl, p0033, enigma, enlight4 and flugplinf. Each has
default/exact arms, P1, a 10-second allowance, seed 3 repeated three times,
presolve off and debug on: 144 runs on Mac/aarch64. Original source hashes,
runtime hashes, non-solver dependencies and JVM options match; MPS has no external
compiler identity. All 60 reported witnesses pass the lab's original-source
solchecker. Native proof claims have no independent proof certificate here.

Both numerical modes preserve gr4x6's 107 checks, zero pending leaves and native
optimum 202.35, and p0033's 257 checks, nine pending leaves and incumbent 3447.
Retained preparation work falls from 1,201,824 to 8,890 on gr4x6 and from
1,283,584 to 3,903 on p0033, using one preparation each. Floating work falls from
10,715,823 to 3,115,592 on gr4x6 but rises from 576,285 to 1,083,929 on p0033.
Median physical whole-CLI measurements expose the retention cost:

| Source / arm | Cold wall (s) | Retained wall (s) | Cold peak RSS (KiB) | Retained peak RSS (KiB) |
| --- | ---: | ---: | ---: | ---: |
| gr4x6 default | 0.76 | 0.78 | 213,008 | 251,120 |
| gr4x6 exact | 0.75 | 0.79 | 212,000 | 235,088 |
| p0033 default | 0.65 | 0.70 | 209,536 | 236,992 |
| p0033 exact | 0.66 | 0.70 | 209,040 | 239,184 |

The finite control proves p0033 at 3089 but leaves gr4x6 at 229.05 after its
allowance. This comparison supports preparation reuse with complete source
coverage, alongside higher retained memory and no observed wall-time improvement;
it does not measure the sparse bound-ordered consumer.

Numerical `engine=ls|alns` selects joint integer/real repair. Compare
`param.max-flips=1` for its initial fixed-discrete rounding with the ordinary
candidate allowance, and retain automatic finite LS and LP/pumping controls at the
same source hashes, revision and settings. `smtCompleteSearchStage_jointRepair*`
counts root guidance, candidate queries, selected integer coordinates, connectivity
and preparation work, compatible owner reuse, paused consultations and checked
improvements. Traversal counts span candidates; peak sizes retain the largest
candidate tree, and unresolved leaves include retired local alternatives.
Incumbent trajectories measure accepted source objectives; independent
witness validation still checks each retained final point separately. Neighborhood
exhaustion supplies no global bound or refutation, and a guidance miss supplies no
performance comparison with an admitted repair.

The complete-worker [comparison 8067](https://lab.eignex.com/jobs/8067) freezes
`6b3e0df4` after the work-accounting correction. It compares automatic finite,
numerical depth-first and sparse frontier modes, default/exact separately, on the
same eight controls plus held-out bell3a, bell5, egout and l152lav. P1, 10 seconds,
seed 3 repeated three times, presolve off and debug on give 216 cases. All share
original source hashes, Mac/aarch64 runtime, JVM options and non-solver dependencies;
all 66 reported final witnesses pass solchecker, and all 216 CLI processes exit
successfully. Default/exact retain the same nominal outcomes in all three repeats.
An earlier [comparison 8057](https://lab.eignex.com/jobs/8057) exposed six l152lav
failures at a single-shot quantum pause. All six corrected frontier runs resume
that pause, retaining one preparation and a checked bound near 4656.36 across
20–22 checks on 1,989 columns. Neither control has a point on this source; the
numerical control declines after one check.

This is a whole-worker comparison, including sparse guidance and its allowances.
The frontier supplies checked global bounds on opt1217, p0201 and enigma where the
numerical control declines. On p0033 it raises the bound from about 2520.57 to
2844.65 but loses the control's incumbent 3447; finite proves 3089. On flugpl the
bound rises from about 1,167,185.73 to 1,183,174.78, with neither numerical mode
finding a point. Finite proves 1,201,500 and refutes enlight4 and flugplinf, while
both numerical modes leave those infeasible sources unresolved.

On gr4x6 all modes prove 202.35. Frontier closes 33 leaves in 45 checks and 48 LP
operations, against 54 leaves in 107 checks/operations for numerical depth-first.
Both have zero pending alternatives. Frontier makes one preparation and 47 reuses,
with 20 inherited-bound closures. Its floating work is 2,070,457 versus 3,115,546;
bound-only certification separately spends 603,409 work units. Instrumented
[capture 8068](https://lab.eignex.com/jobs/8068) checks a root bound of 185.55,
then global bounds 197.6, 2827/14 and 202.35 at checks 23, 40 and 45. Its incumbents
206.5 and 202.35 arrive at checks 28 and 36. [Depth-first 8059](https://lab.eignex.com/jobs/8059)
finds six incumbents at checks 18 through 82 and proves the last one at check 107.
The earlier [frontier 8058](https://lab.eignex.com/jobs/8058) has no root bound,
179 certificate-phase consultations and 104 unresolved alternatives. Reusing the
bounded dual reconstruction schedule makes those checked bounds available to the
same frontier; the queue alone supplies no proof.

Held-out bell3a, bell5 and egout advance from one declined numerical check to
403, 453 and 329 frontier checks, with one preparation each. They retain 166, 180
and 163 pending alternatives and have no frontier incumbent. Egout has a checked
bound near 150.97; bell3a and bell5 have no global bound. Finite finds points on
bell3a and egout. Physical whole-CLI medians show the
cost and outcome tradeoffs; timings with different completion or witness results
do not establish a speed ranking:

| Source / arm | Numerical wall (s) | Frontier wall (s) | Numerical peak RSS (KiB) | Frontier peak RSS (KiB) |
| --- | ---: | ---: | ---: | ---: |
| gr4x6 default | 0.76 | 0.47 | 231,856 | 213,536 |
| gr4x6 exact | 0.75 | 0.48 | 236,736 | 216,944 |
| p0033 default | 0.66 | 0.59 | 238,448 | 223,968 |
| p0033 exact | 0.67 | 0.60 | 232,736 | 236,704 |
| l152lav default | 0.41 | 10.10 | 210,240 | 354,016 |
| l152lav exact | 0.39 | 10.10 | 208,928 | 361,408 |

Operation counts include small exact fallback consultations; bound-only
certification itself stays inside its retained operation. The supported foundation
is source-session reuse, resumable wide guidance and consumed complete frontier
coverage, with a gr4x6 proof/cost improvement and weaker feasibility progress on
p0033 and the held-outs. Joint repair consumes the same authority in the comparisons
below. Checked scoped LP refutations are qualified below as well. Native optimum/refutation
claims have no independent proof certificate here.

[Joint repair qualification 8045](https://lab.eignex.com/jobs/8045) compares initial
rounding, joint LS/ALNS and automatic finite LS/LP over eight frozen mixed sources
at `a4121e1c37`: exact policy, P1, ten seconds, presolve off, three seeds and
debug enabled on Mac/aarch64. All 16 reported final witnesses pass independent
solchecker; native proof claims have no independent proof certificate here.
On gr4x6, rounding finds no incumbent while both joint arms produce five checked
improvements ending at 210 in every seed. Finite LP reaches 226.35–229.05 and
finite LS 311.55–375.4. Joint repair finds no flugpl incumbent, where finite LP
proves 1201500. Four wider roots supply no scalar guide. Each gr4x6 joint run uses
one LP preparation and 255 reuses; the operation allowance ends search before
the finite controls' wall allowance, so elapsed times establish no general speedup.

[Sparse consumer qualification 8056](https://lab.eignex.com/jobs/8056) compares
that scalar control with `4f4d29ef72` on the same eight sources plus bell5, pp08a
and rgn: 231 runs with matching source hashes, runtime, dependencies and JVM
options under the same policy on Mac/aarch64. All 34 reported final witnesses
pass independent solchecker. The gr4x6 outcomes repeat across all three seeds:

| Repair | Final objective | Distinct checked incumbents |
| --- | ---: | ---: |
| Initial rounding | None | 0 |
| Scalar LS | 210 | 5 |
| Sparse depth-first LS | 230.85 | 3 |
| Sparse ALNS | 214.8 | 4 |
| Sparse frontier LS | 206.5 | 3 |

Sparse frontier's trajectory is 226.85 → 214.8 → 206.5. Sparse ALNS closes four
nonimproving neighborhoods with checked cutoffs. Each joint arm spends 256 source
LP operations; depth-first LS and ALNS admit 161 and 177 theory checks, respectively,
with mixed guidance/fallback operation costs. Frontier uses 11,636 preparation,
7,478,399 floating and 924,881 bound-certification work units, with one preparation
and 255 reuses. These are work counters, not physical allocation measurements.
The weaker depth-first objectives reject a general improvement claim.

All seven wider sources receive sparse root guides, but none yields a joint
incumbent. Bell3a completes 241 depth-first candidates as undecided, with no checked
refutation; these runs supply no wide scoped Farkas refutations.
Finite LP retains flugpl's proof and feasible egout, pp08a and rgn points; finite
LS also finds bell5 points. The evidence supports the explicit joint operator and
frontier option, while retaining automatic routing and the wide-neighborhood
certification boundary. Native proofs remain separate from independent point checks.

[Final joint integration 8072](https://lab.eignex.com/jobs/8072) repeats all 231 cases
at `fe25a362c2` with the completed bound service and corrected work accounting.
All eleven source identities, runtime, dependencies and JVM options match within
the Mac/aarch64 comparison; all 42 reported final witnesses pass solchecker.
Across all three seeds, gr4x6 depth-first repair reaches 214.8 in four checked
incumbents, ALNS reaches 210 in five, and frontier repair follows
206.5 → 202.35 with nine scoped cutoff closures. Rounding finds no point.
The finite LP control also reaches 202.35. Frontier spends 256 LP operations,
11,636 preparation, 7,237,102 floating and 1,755,424 bound-certification work units,
with one preparation and 255 reuses; ALNS closes five neighborhoods by cutoff.
All seven wider roots retain guides but have no joint incumbent. Finite LP produces
21 final points across 33 cases, finite LS nine, and each joint arm three.
The operator therefore remains explicit: it provides coordinated checked repair
and local bound closure, with no global proof or general performance claim.

#### Checked sparse refutations

[Qualification 8087](https://lab.eignex.com/jobs/8087) compares `2e30af709f` with
`ce2e9e65f0`: fifteen frozen MPS sources, frontier, joint LS and joint ALNS,
three seeds, exact/P1/10 s, presolve off and debug/source validation enabled.
All 270 CLI processes exit successfully on Mac/aarch64 with matching original
hashes, runtime, non-solver dependencies and 3 GiB Serial-GC JVM options. All 135
pairs preserve feasibility, objective and native proof outcomes; all 26 published
final witnesses pass independent solchecker. Native proofs have no independent
proof certificate here.

Scoped Farkas evidence closes additional wide alternatives under the unchanged
256-operation allowance. These frontier counts repeat in all three seeds:

| Source | Checks | Checked LP conflicts | Closed leaves, baseline → candidate | Pending, baseline → candidate |
| --- | ---: | ---: | ---: | ---: |
| bell3a | 403 | 55 | 36 → 91 | 166 → 111 |
| bell5 | 453 | 30 | 47 → 77 | 180 → 150 |
| egout | 329 | 92 | 2 → 94 | 163 → 71 |
| dcmulti | 423 | 45 | 16 → 61 | 196 → 151 |
| enigma | 371 | 68 | 52 → 120 | 134 → 66 |

Bell3a separately spends 1,843,912 Farkas certification work units. Its joint LS
arm refutes 171 of the same 212 repair candidates, against none in the baseline;
those refutations cover only their respective repair scopes. The 111 remaining
frontier consultations are refused after the operation allowance is spent, with
whole solves finishing near 1.4 s. They carry no refutation authority.

[Initial comparison 8082](https://lab.eignex.com/jobs/8082) loses dcmulti's ALNS
witness in all three seeds. [Profile 8086](https://lab.eignex.com/jobs/8086) points
to repeated numeric-context reconstruction and source proof checking through its
sampled caller/allocation tables; sample shares establish no speedup. Reusing an
invocation's checked bound for equal or weaker coverage restores objective 194049
in every 8087 seed. Median physical CLI wall time remains 7.38 → 8.13 s, with peak
RSS 299,776 → 312,464 KiB. Bell3a frontier changes 1.32 → 1.43 s and
243,504 → 270,128 KiB. These are measured costs alongside stronger coverage.

Modglob and pp08a gain no LP conflicts. Flugplinf explores deeper, from 227 to 319
checks and 71 to 97 closed leaves, while pending leaves rise from 43 to 63 and
the native result remains unknown. Bound availability, the operation allowance and
incumbent acquisition remain limits; automatic routing is unchanged. This
qualification supports consumed scoped refutations, not a general runtime or
optimization improvement.

#### Checked source rounding cuts

[Qualification 8117](https://lab.eignex.com/jobs/8117) compares `f59091a21a` with
`b0c559df3a`: the same fifteen source hashes as 8087 plus p0201, p0282, stein27
and stein45 holdouts, frontier and joint LS, three seeds, exact/P1/10 s, presolve
off and source validation enabled. All 228 cases complete on Mac/aarch64 with
matching source hashes, runtime and 3 GiB Serial-GC options. All 114 pairs retain
feasibility, objective and native proof outcomes; all twelve published final
witnesses pass solchecker. Native cut and bound checks are separate from these
independent witness checks and provide no exported proof certificate.

The single-row rounding family retains four checked cuts on p0033, whose source
SHA-256 is `8ccff819023237c79ef32e238a5da9348725ce9a4425d48888baf3a0b3b42628`.
Its frontier results repeat in all three seeds under 256 LP operations:

| Observation | Baseline | Rounding |
| --- | ---: | ---: |
| Checks / fractional splits | 295 / 147 | 359 / 179 |
| Checked coordinate conflicts | 12 | 22 |
| Closed / pending alternatives | 121 / 27 | 99 / 81 |
| Preparation work | 5,154 | 7,133 |
| Floating work | 1,227,470 | 1,764,384 |
| Bound certification work | 644,562 | 946,059 |
| Farkas certification work | 705,814 | 676,113 |

The final checked lower bound weakens from
`3270739229377822693/1125899906842624` to
`12811164679999481365/4503599627370496`. Cut construction spends 1,067 modeled
support operations; query proof checking spends 27,284 steps, included in bound
certification work. Joint LS also consumes the cuts, but its separate uncut root
guide and cut query require two matrix preparations instead of one. The other
eighteen sources, including all four holdouts, retain no eligible cuts. The
holdouts preserve frontier checks, splits and pending counts. These observations
justify the source-checked integration as an explicit frontier option, with no
general search or runtime improvement claim; ordinary guidance leaves cuts off.
Small deterministic controls check a bound increase from 3/2 to 2 and a checked
root refutation beyond exact-primal admission.

The qualification revision reports borrowed-authority cut stage counters
cumulatively in joint repair; use its canonical `smtSourceLp*` counters for costs.
Traversal work deltas and maximum retained/active-cut gauges avoid that repeated
sum. Interrupted cut preparation and checking report their consumed work. The
rounding family and proof rules are unchanged by those accounting corrections.

#### Checked sparse primal recovery

`numericalPrimalUnavailable`, `numericalPrimalFractional` and
`numericalPrimalIntegral` classify completed sparse proposals per consultation.
`PrimalSourceRejected` and `PrimalSourceUnavailable` distinguish source-check
outcomes; `PrimalExactDimension` records the full query's small exact-fallback
boundary. `PrimalRecoveryReconstructed` and `PrimalRecoveryResidual` identify
candidate production, while `PrimalRecoverySourceChecked` requires original-source
acceptance. Recovery limits and unavailable residual components leave alternatives
unresolved. `PrimalRecoveryWork` and `PrimalRecoveryAllocation` report modeled
recovery preparation/arithmetic deltas; nested source LP costs remain in their
existing counters. These are distinct from physical time, RSS and allocation.
Frontier observations use the `numerical` prefix; joint candidate traversals use
`jointRepair`, and root preparation uses `jointRepairRoot`. Retained attempt or
component reuse has its own counter, and consultations are not distinct incumbents.
`PrimalRecoveryComponentMaxColumns` and `PrimalRecoveryComponentMaxRows` retain
prepared residual shapes. `PrimalRecoveryComponentDimension` counts those outside
small exact admission; attempts stopped before preparation have no such classification.

[Diagnosis 8121](https://lab.eignex.com/jobs/8121) pins diagnostic-only `78b9c16d48`
on eleven sources from 8072: frontier and joint repair, exact/P1/10 s, presolve off,
seed 1 and debug/source validation enabled. All original hashes match 8072, with
one Mac/aarch64 runtime, dependency set and 3 GiB Serial-GC options. Both gr4x6
final witnesses pass solchecker; native proof claims remain separate.
Every wider frontier proposal is fractional. Joint repair reaches integral,
source-rejected proposals on bell3a, modglob and rgn at 18, 222 and 206 consultations,
respectively. These observations select real reconstruction and continuous
residual completion for integral assignments; fractional guides continue to order
exhaustive integer splits.

[Comparison 8151](https://lab.eignex.com/jobs/8151) pairs `78b9c16d48` with
`ae162bf8fe` over those eleven sources, both consumers, default/exact policies and
three repeats at seed 1: 264 completed cases on one Linux/amd64 spot node.
Original hashes, runtime, non-solver dependencies and 3 GiB Serial-GC options match;
all 30 reported final witnesses pass solchecker. Twelve of 132 pairs improve and
the other 120 retain feasibility, objective and native proof outcomes. Native
proofs remain separate from independent witness validation.

In every repeat and policy, gr4x6 joint repair improves 210 to 202.35, with six
checked incumbents instead of five. Seventeen reconstructions spend 58,493 modeled
work units and 2,210,109 cumulative allocation bytes. Its peak candidate tree stays
at 57 nodes; frontier retains its 45-check native optimum. Bell3a gains a joint
incumbent of 899922.1 in all six runs. Recovery spends 749,371–789,353 work and
28.8–30.3 MB modeled allocation; the peak candidate tree falls from 19 to three
nodes. Exact-arm median process elapsed rises from 4.639 to 10.249 s, with the
candidate using 157–162 source operations before its wall allowance instead of
the baseline's 256-operation stop. These are checked-point gains with costs,
without a general runtime improvement claim.

Modglob and rgn retain no point. Their 119 and 113 unavailable component attempts
spend 4,385,144 and 2,567,399 modeled work, and 149.9 and 96.4 MB cumulative modeled
allocation. Additional source LP consultations reduce candidate coverage from
229 to 121 and 215 to 124 at the same 256-operation allowance. Their shorter
elapsed times therefore establish no throughput gain. Connected residual completion
is a remaining recovery boundary; fractional wider frontier guides retain their
ordinary split path. This comparison measures standalone recovery under that
operation policy and preserves automatic routing.

[Probe 8170](https://lab.eignex.com/jobs/8170) runs the funding-integrated
`9cc78dd97a` on modglob and rgn with exact/P1/10 s and the same source hashes.
Both remain unresolved. Modglob's prepared residual maxima are 323 columns and
188 rows, outside small exact admission; rgn's 81 columns and 42 rows fit its
dimension guard but still decline completion. The probe makes no paired timing
claim. [#3568](https://github.com/Eignex/klause/issues/3568) tracks larger residual
completion and deeper diagnosis of the within-dimension decline.

[Diagnosis 8217](https://lab.eignex.com/jobs/8217), at `117f3e0d2e`, retains
8170's original hashes, exact/P1/10 s, seed 1, numerical joint repair and presolve
off on Mac/aarch64. Rgn exceeds small exact scalar-bit admission. Seven sparse
residuals complete through exact primal basis verification and supply 254 retained
component reuses; its final 104.39999928 witness passes independent solchecker.
This is a feasible-point claim, without a native optimality proof. Modglob reaches
nine floating feasible candidates but each exhausts the 2M exact-primal work
allowance; 115 unchanged declines reuse their result. Those failed basis attempts
spend 17,074,248 work and 358,453,908 modeled allocation bytes in total, separately
from residual assembly and floating work. The allocation is not measured RSS, and
this diagnostic establishes no paired speedup or default-route quality advantage.

[Logical-core completion 8301](https://lab.eignex.com/jobs/8301), at `cac11c3af2`,
keeps those source hashes, runtime, options, compiler and Mac/aarch64 identity.
Removing basic logical unit columns completes three modglob residuals with 190
reuses under the same 2M/64 MiB certificate envelope. Their combined point and
basis checks spend 677,762 work and 19,620,135 modeled allocation bytes; maximum
factor fill is 63 and scalar width 67 bits. Its independently accepted final point
is 312401375.2256091, without a native optimality proof. Rgn completes seven
residuals with 248 reuses and retains the independently accepted 104.39999928
point, also unproved.

Modglob's cumulative modeled funding allocation changes from 537,819,015 to
405,410,349 bytes, and repair checks from 148 to 256. Rgn changes from 358,092,536
to 337,355,542 bytes and from 410 to 403 checks. These are bounded progress and
cost observations on changed search trajectories, not a general throughput claim.
Residual assembly remains a substantial allocation cost, and feasible completion
alone establishes no objective-quality advantage over strong defaults.

[Qualification 8302](https://lab.eignex.com/jobs/8302) compares `f1c8bddd7e` with
its `dc3560c3ac` common base and retains the `0b9f22b574` dispatch defaults as a
separate strong control. All 120 cases use the same five frozen original sources,
Mac/aarch64 runtime, compiler, JVM options and independent checker identity:
P1, 30 s, debug enabled, seeds 1/2/3, default and exact policies separately.
Companions use `engine=mixed` and `param.linear-traversal=numerical-companions`;
all arms retain default presolve. The CLI uses a 3 GiB heap, Serial GC and one
active processor.
All 108 reported witnesses pass solchecker; native proofs are not independently
proof-checked. No case fails.

| Source | Current companions to residual completion | Strong controls |
| --- | --- | --- |
| modglob | No point to checked 312428931.7216991 in all six cases | Default-mode finite points near 20784597.9163945 remain stronger; exact finite controls have no point. |
| rgn | 224.19999994 to 97.59999892 in all six cases | Default finite points remain stronger at 82.19999924; exact finite points range from 115.9999998 to 157.20000044. |
| bell3a | Per-seed objectives match in both policies: 899922.1, 878430.316, 878430.316 | Finite quality varies; completion supplies no native proof. |
| p0033 | Native optimum 3089 in every pair | All finite controls retain the same native optimum. |
| gr4x6 | Native optimum 202.35 in every pair | All finite controls retain the same native optimum. |

Gr4x6 keeps 45 numerical checks, 65 retained nodes, zero pending leaves, nine
incumbent-bound closures and 1,942,663 floating work in every current-companion and
treatment case. Physical whole-CLI measurements use external time without JFR;
the following are medians across three seeds, not independent timing repeats.

| Source and policy | Peak RSS KiB, current to recovery | Wall seconds, current to recovery |
| --- | ---: | ---: |
| gr4x6 default | 209632 to 209232 | 0.58 to 0.58 |
| gr4x6 exact | 206928 to 206336 | 0.58 to 0.58 |
| modglob default | 472800 to 493664 | 30.11 to 30.10 |
| modglob exact | 481968 to 476368 | 30.10 to 30.10 |
| rgn default | 563008 to 486592 | 24.03 to 22.83 |
| rgn exact | 539280 to 486304 | 24.21 to 22.85 |

The gr4x6 current finite defaults take median 4.93/4.39 seconds and
305936/303152 KiB under default/exact policies. The companion proof/resource
benefit survives; recovery adds no gr4x6 numerical work. Modglob's default-mode
RSS increase is a cost of the wider recovery path. These observations establish
bounded feasibility and quality progress, not a general speedup or admission of a
replacement for the stronger finite defaults. [Objective refinement](https://github.com/Eignex/klause/issues/3607)
separates the remaining continuous-choice and discrete-pin objective gaps while
preserving accepted feasible points.

`bench.profile.MpsProofFrontier` takes `suite=ID problem=NAME sha256=HEX exact=true|false`
for one hash-matched original source. It uses the same numerical source setup and
invocation, seed 3 and a ten-second solve deadline under the existing LP allowances.
`frontier=true` selects the retained bound-ordered owner; `cuts=false` disables its
source separation. `cuts.json` retains the last query's cut rows, source premise
references, exact combination weights, continuous bound derivations and fresh
native source checks. Eligibility counters cover only visited source rows and
distinguish missing bounds, integral shifted right sides and retention limits.
Submit through the lab with `profile.runTimeoutSec: 180` for a bounded local Mac
capture. A child JVM uses one processor, a 3 GiB heap, Serial GC and JFR. This is an
instrumented owner-state capture; ordinary matched CLI experiments measure cost.

The capture retains the original bytes, native affine system and build/runtime
identity. `incumbents.jsonl` flushes accepted incumbent values and branch conditions.
`bounds.jsonl` flushes changed global bounds after cooperative slices, recording
their native-owner check separately from independent proof validation.
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
Optional `frontier=true` selects bound-ordered traversal and retained sparse
guidance. Wider contexts use the sparse preparation policy rather than the small
exact-primal admission; unresolved alternatives retain the same capture checks.
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

#### Checked mixed source rows

[8265](https://lab.eignex.com/jobs/8265) compares `3c9a421ec7` mixed residual
rounding, separation disabled, the `0b9f22b574` pure-rounding frontier and its
automatic defaults. Thirteen original MPS identities, seeds 1/3, P1 and ten-second
default-tolerance budgets produce 104 cases on Mac/aarch64. All 36 reported
witnesses pass solchecker. Source hashes, runtime/options, non-solver dependencies
and compiler identity match across arms; solver/CLI identities remain revision-bound.

Each seed retains and activates 40 mixed cuts on `rgn` and 16 on `pp08aCUTS`.
Both complete the bounded row scan. Small-source declines distinguish integral
shifted right sides from missing continuous bounds; `fiber`, `gesa2-o` and
`cap6000` exhaust row preparation, so their zero counts establish no complete
eligibility result. The four pure cuts on `p0033` preserve its 3089 proof at
1995 checks, versus 2081 with separation disabled. All numerical `gr4x6` controls
retain the 202.35 proof at 45 checks.

The new family establishes capability without a cost improvement. On `rgn`,
mixed cuts leave native bounds near 73.6, versus 75.0/73.6 with separation disabled.
They close 185/171 leaves versus 276/252 and spend 164.9M/150.7M floating work
versus 101.6M/97.0M, plus 3.60M/3.26M cut-proof steps. Working rows grow from 46
to 86; reported modeled LP allocation grows from 262,364 to 421,164 bytes.
These are modeled costs, not physical memory or throughput measurements.
Neither numerical arm finds an incumbent on `rgn`; automatic defaults retain
checked witnesses near 82.2. Eligibility and retained consumption justify the
bounded mechanism; these results supply no numerical-default admission or speedup
claim. Cut efficacy and selection need separate evidence.

[8299](https://lab.eignex.com/jobs/8299) captures `rgn` at `b1e32dd019`, seed 3,
with the same source hash and default-tolerance policy. All 40 retained cuts pass
fresh native checks under the last query's recorded scope, across 637 retained
nodes and 357 LP reuses. The first mixed derivation scales its original row by
`1/230000001` and cites four continuous bounds; rounding changes residual magnitude
from `50000000/230000001` to `25000000/107499989`. Original bytes, exact rows,
weights, bound citations and scopes remain in the capture. This instrumented
inspection supplies neither an independent proof check nor a whole-CLI timing claim.
