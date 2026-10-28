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

The bench exits with `75` (`EX_TEMPFAIL`) when corpus transport remains unavailable
after bounded download retries, or the cache publication service is unavailable.
The lab treats this preparation outcome as an infrastructure retry. Permanent HTTP
errors, invalid inputs and solver outcomes retain their ordinary failure semantics.

`bench.profile.DivModConstructionProfile` times XCSP3 parsing and lowering without
solving. Use `sizes=1024,2048,4096,8192 repeats=3 warmups=1` for generated sources
that repeat each modular expression twice. Optional `suite=ID problem=NAME sha256=HEX`
adds one hash-pinned catalog input. Sources, per-iteration construction times,
compiled sizes, factor kinds and build/runtime identities stay under `$OUT`.
Compare the identical profile source and settings at both revisions on one host.
JFR construction timings are separate from uninstrumented, source-checked solve
controls and establish no solver throughput or verdict claim.

## Klause-lab

`bench.profile.CoreQueryProfile` compares retained and ordinarily prepared Boolean
core controls with unrelated cheap objective pins. Two source shapes, five OLL
policies and three repeats give 60 two-second core cases, alongside 24 matched
native-SAT BnB controls with LP off/forced bounding. Allow 300 seconds for the full
profile. `confirm=true` selects one equal-weight source, both preparation policies
and one repeat: ten core cases, four BnB cases and four helper calls, each capped at
two seconds.
It retains source/objective hashes, actual query/core
sequences, source point checks, preparation and complete-handle time/allocation,
work and thresholds. Policies include removing root-satisfied cost pins, bounded
propagation deletion (`refined`) and seed reason projection (`projected`). The
opt-in propagation-deletion control shares the worker's
allowance. Separate helper calls retain the existing opt-in deletion minimizer's
actual seed-path behavior. These native controls establish no independently checked
proof or whole-CLI throughput claim.

`bench.profile.CoreQueryLearningProfile` captures six bounded completed OLL query
sequences over the incidental-`q` control, with retained/default preparation and
three repeats. It replays the identical queries with and without a source-checked
unit from an earlier completed query. Only source-coordinate `q` is eligible;
remapped prepared coordinates decline. Every source check receives at most 512
decisions and 500 ms under one 120-second capture deadline. It records actual pins,
candidate checks, query construction/search time and allocation, source-point
checks and visits to `q=false`. A conditioned `q or a` negative rejects exporting
`q` from `a=false`. This is a bounded replay experiment, not a mutable query owner
or ordinary slice-retention measurement.
`confirm=true` selects one repeat of each preparation policy, including checked
source outcomes and actual `q=false` assertion attempts. It is an observation
confirmation rather than a timing repeat.

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

`nonlinear-integer-cells-v1` fixes seventeen univariate cell-consumer controls,
with eight core cases and nine cases from held-out families. The matching
`smtlib-nonlinear-integer-cells-v1` suite separates mathematical expectations from
bounded integer-search outcomes. The `-core-v1` and `-holdout-v1` named sets retain
their complete memberships. The [fixture notes](smoke-corpus/nonlinear-integer-cells-v1/README.md)
describe repeated tuple support, exact endpoints, conditional predicates, mixed
completion and degree limits; the manifest there pins source hashes and licences.

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

`qfnra-coupled-v1` fixes twelve connected-section controls, selected through
`smtlib-qfnra-coupled-v1`: inconsistent product cycles, SAT neighbours and guarded
alternatives, shifted and repeated roots, strict/weak boundaries and an uncovered
singular fibre. Its [fixture notes](smoke-corpus/qfnra-coupled-v1/README.md) and
manifest retain the original hashes. `qfnra-prepared-support-v1` separately fixes
the three SAT sources from job 8750, with [unchanged bytes](smoke-corpus/qfnra-prepared-support-v1/README.md)
and a matching `smtlib-qfnra-prepared-support-v1` suite.

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

`-Dklause.bench.corpus.shared=true` reuses the normal XDG/home cache while
`-Dklause.bench.corpusCache` names an isolated preparation workspace. Only requested
collections enter that workspace, using hard links on the same filesystem and
copies across filesystems. Collection fetches hold a process lock and publish by
rename after completion; interrupted staging is discarded. HTTP downloads use
10-second connection and 30-second read timeouts, retry transient I/O and HTTP
failures up to four attempts, and publish only complete responses. Permanent HTTP
errors fail immediately. Parallel processes using shared storage must disable eviction.

`KLAUSE_BENCH_CORPUS_MIRROR=https://.../corpus-packs` enables a provider-independent
HTTP read-through cache. A local hit makes no mirror request; a cold collection first
restores `<collection>.tar` from the mirror, falling back to its original source only
on HTTP 404. Restored packs are bounded and reject filesystem links and traversal.
`KLAUSE_BENCH_CORPUS_MIRROR_WRITE=true` publishes newly fetched collections once using
conditional HTTP PUT. Configure the cloud URL for workers in the store's region;
native execution should reuse its local corpus cache. `KLAUSE_BENCH_CORPUS_KEEP_PACKED=true`
retains compressed originals when resolving plain input files, allowing the lab to
reference existing compressed remote objects rather than uploading plain duplicates.

ZIP collections extract through the JDK with entry checksum and destination checks;
failed ZIP fetches remove partial collections. LZMA members use XZ for Java;
XZ, bzip2 and gzip members use `unxz`, `bunzip2` and `gunzip`.

CNF, WCNF, OPB, WBO, XML, SMT-LIB and MPS collections can use `.zst`; MiniZinc
source/data and cloned collections stay plain. zstd-jni handles stored frames in
the bench JVM. `select files=true` replaces selected compressed members with their
original plain bytes for CLI and checker consumers, and refreshes collection sizes.
This decoding belongs to selection preparation; ordinary cases use the prepared
plain inputs. Hashes use decompressed bytes.
Compression writes beside the original, verifies SHA-256, then replaces it;
interrupted migration can resume.

## Objective-preserving symmetry admission

`bench.profile.SymmetryAdmissionProfile` captures ordinary protected preparation
and opt-in `default,+objective-symmetry` preparation for one to eight hash-pinned
XCSP3 optimization sources or `wcnf-core` sources. Submit it as a lab profile with
`request64=` containing a base64 JSON array of `{suite, problem, sha256}` entries. Each preparation has an
eight-second containing token and 550 million phase units. The capture retains
original sources, replacement objectives, source symmetry markers, moved generator
coordinates and their coefficients, retained integral rows, root integer domains,
pass outcomes, elapsed time and charged work. Tagged row references use the IR's
`Term` encoding; diagnostic rows retain exact coefficients, bounds and activators.
It executes no search and makes no source-verdict or runtime-gain claim. Stopped
preparations remain incomplete observations. Use ordinary source-checked lab solves
for consumed pruning, quality, proof and total-cost qualification.

Objective-preserving admission control [8865](https://lab.eignex.com/jobs/8865),
at `c8e09efafc`, uses the self-authored Latin4 source, normal preparation,
`conflictDriven`, P1, seed 3 and five seconds per case on Linux/amd64, JDK
25.0.4.1+1-LTS, with a hard one-vCPU/4608-MiB cgroup. Protected admission and
symmetry disabled both visit 977 nodes and perform 5,094 propagations; opt-in
admission visits 245 nodes and performs 1,361 propagations through the existing
generator consumer. All three attain native optimum 24, with independently
accepted original-source witnesses. Witness checking does not certify the proofs.
The admitted transpose moves twelve cost coordinates and adds one propagator-only
factor, with no scalar orders. Preparation costs rise from 139 to 203 ms and
50,802 to 62,042 phase units. Whole-CLI wall/user-CPU/peak-RSS observations are
2.20 s / 1.98 s / 109,448 KiB protected, 1.90 s / 1.63 s / 108,296 KiB admitted,
and 2.31 s / 2.09 s / 119,364 KiB disabled. One ordered repeat establishes consumed
pruning on this fixture, not a runtime speedup or ordinary-source gain.

Preparation screen [8866](https://lab.eignex.com/jobs/8866), at `8e4dd749a1`,
completes all ten captures without a stop. Latin4 retains its transpose after normal
preparation. Warehouse-40-80-00, GBACP-UD04, TestScheduling-t020m10r03-1 and
BinPacking2-n1c1w1a retain no generator in either mode. These four ordinary sources
cover explicit costs, nonlinear cost auxiliaries, a maximum-output objective and
source symmetry rows. Their extra objective-symmetry analyses spend respectively
898,844, 49,699,697, 7,151,610 and 246,015 phase units without a reduction. Affine
and binary replacement remain enabled; the prepared objectives and complete source
hashes are retained in the capture. BinPacking2's source symmetry rows are present
although the existing XCSP3 source marker reports false, so no marker-based skip
is inferred. The ordered JFR preparations are applicability and cost observations,
not search or timing comparisons.

This gate retains an opt-in consumed capability and rejects broader/default
admission on the screened evidence. No ordinary-source benefit or mixed-local-search
qualification follows. Generator handling has no local-search invariant; scalar
orders still change the mixed portfolio's grading landscape, retaining the earlier
trapping and cost losses as admission constraints. The four empty screens provide
no reason to expand a mixed comparison. Raw records, original sources, hashes and
resource receipts remain outside Git in
`klause-evidence/campaigns/objective-preserving-symmetry-2026-10-28/`.

## Conditional symmetry admission

`bench.profile.ConditionalSymmetryProfile` takes no arguments and captures the
hash-pinned `wcnf-core/conditional-cost` source. Four preparations compare default
and opt-in objective symmetry, with and without fresh opening `s=t=true` pins.
Each preparation has eight seconds and 550 million phase units. The two unpinned
views retain internal pins and undo in one propagation session, then run ordinary,
explicit LP-bound, LP-off, PB-cutoff and prefix-first optimization controls, each
with five seconds and seed 3. The prefix-first control disables LP and chooses
`s,t` before objective-guided remaining choices. Search captures include their
instrumentation and retain at most 32 branch states per run; these are applicability
observations, not timing comparisons. Normal LP admission skips clause-only models,
so the explicit bound control is distinct from ordinary configuration.

Snapshots retain immutable factors and Boolean values. They omit learned constraints
and do not certify the complete enforcing residual, authorize pruning, or supply an
independent source check. Fresh opening preparation is distinct from decisions in a
retained search. The profile installs no conditional symmetry consumer. Ordinary
lab solve cases retain original WCNF bytes and witnesses; the lab has no independent
WCNF checking adapter, so these cases receive only native checking credit.

[Lab 8891](https://lab.eignex.com/jobs/8891), at `357d4e4885`, completes all four
preparations and ten searches on Linux/amd64, JDK 25.0.4.1+1-LTS, with a hard
one-vCPU/4608-MiB cgroup. Preparation retains all three original clauses and exact
coefficients `[1,1,-1,-2]` with constant 3. Both admission modes have zero generators;
fresh opening pins retain all five clauses and also expose no generator. The
retained session reaches `???? -> ??1? -> ??11` and undo restores `????`, in source
order `a,b,s,t`. Thus the connected source residual exists without a root generator
or a preparation reduction.

All ten searches return native optimum 1. Both preparation views give the same
counts: ordinary, LP-off and PB-cutoff use nine nodes/four failures; prefix-first
uses eight/four; explicit LP uses four/one with root bound 1 and 978 LP work units.
Ordinary branch traces never leave both `a,b` free at `s=t=true`. Prefix-first does,
finds `0111`, and revisits that prefix after root model blocking. That blocker is an
enforcing relation, so the original three clauses alone cannot certify the later
residual swap. Even without LP, the incumbent's cheap linear bound closes the
`a=true` sibling. The source inequality `a+b >= s+t-1` gives the stronger root bound
`C >= 2-t >= 1`, attained by either exchanged point. These controls reject adding a
dynamic consumer at this gate; they do not reject conditional symmetries generally.
No broader screen or mixed-local-search/default admission follows.

[Lab 8884](https://lab.eignex.com/jobs/8884), at `38d6ff2e8f`, completes two whole-CLI
controls on the same source hash, P1 and seed 3. Ordinary and LP-off both report
native optimum 1, witness `1011`, seven nodes and two propagations. Both spend 4,670
preparation units; preparation is 136/116 ms, CLI wall time 1.40/1.37 s, user CPU
1.17/1.17 s and peak RSS 115,456/115,296 KiB. They run concurrently under a hard
two-vCPU/8704-MiB cgroup. These single-repeat costs and ordered JFR captures are not
speedup evidence. Neither witnesses nor proofs receive independent checker credit.
Raw states, timings, source/build/runtime hashes and resource receipts remain in
`klause-evidence/campaigns/conditional-symmetry-3760/` outside Git. Queued profile
8883 was cancelled before execution when the explicit LP control was added.

## Equality and complemented Boolean controls

[Preparation 8880](https://lab.eignex.com/jobs/8880), at `49289e403e`, completes
four captures of the independent [cost symmetry sources](smoke-corpus/cost-symmetry/README.md).
The transportation source retains all nine 0..2 domains and its original objective.
Dependent-row removal drops the column-0 equation, leaving three row equations and
two column equations. The exact source cost certificate still uses retained rows,
with zero right side, but transpose fails full factor-multiset verification on this
prepared representation. Neither protected nor coefficient-colored preparation
finds a generator. The latter adds 328 phase units without a reduction.

[Transportation 8878](https://lab.eignex.com/jobs/8878) proves 90 in all three
ordinary controls, each with 63 nodes and 230 propagations. All three original-source
witnesses pass the independent XCSP3 checker. Disabling LP harvest leaves the tree
unchanged; these directly selected `conflictDriven` workers report no LP work, so
this comparison does not establish that a tight LP bound was consumed.

[Boolean 8879](https://lab.eignex.com/jobs/8879) proves 2 in all four controls.
Preparation preserves the signed weights `[1,-1,1,-1]` and offset 2. Existing
objective admission finds the two unsigned swaps and scalar orders, reducing
10 nodes to 7 while adding 3,707 preparation units. Direct core-guided search
uses two cores, an exact lower bound of 2 and two nodes. Its existing preferred-literal
orientation retains positive penalty weights and the source constant. The four
optimal source assignments already form one orbit under the unsigned swaps;
the complemented reversal's additional source orbit relates nonoptimal points.
WCNF has no independent lab checker here: its witnesses and native proof labels
retain the `reported-result-v1` limit.

[Strong LP 8906](https://lab.eignex.com/jobs/8906) selects the `lp-aggressive`
worker on both sources. Transportation attains 90 and closes the root with its
certified bound of 90, zero search nodes and one bound prune. Root LP work is
1,518 units; the node LP spends 1,235 units. Its original-source witness also
passes the independent XCSP3 checker; none of these witness checks certifies a
native optimality proof. The Boolean control reports no LP work and retains
10 nodes, so it supplies no LP-consumption claim. Core-guided search remains
the stronger existing Boolean control.

All nine solves use normal preparation, P1, seed 3 and five-second budgets on
Linux/amd64 with JDK 25.0.4.1+1-LTS, a hard one-vCPU/4608-MiB cgroup and
`-Xmx3g -XX:+UseSerialGC -XX:ActiveProcessorCount=1`. Physical whole-CLI
measurements include construction and search; they are one ordered repeat,
not a throughput comparison. The JFR preparation captures are separate diagnostics.

| Source and control | Nodes / propagations | Preparation ms / work | CLI wall / user CPU s | Peak RSS KiB |
| --- | ---: | ---: | ---: | ---: |
| Transportation protected | 63 / 230 | 249 / 164219 | 1.52 / 1.32 | 102664 |
| Transportation coefficient-colored | 63 / 230 | 264 / 164547 | 1.55 / 1.31 | 102816 |
| Transportation without LP harvest | 63 / 230 | 196 / 164219 | 1.51 / 1.22 | 103308 |
| Boolean protected | 10 / 6 | 178 / 2773 | 1.52 / 1.18 | 95864 |
| Boolean unsigned admission | 7 / 7 | 138 / 6480 | 1.36 / 1.12 | 100360 |
| Boolean symmetry disabled | 10 / 6 | 155 / 2563 | 1.34 / 1.04 | 96356 |
| Boolean core-guided | 2 / 3 | 163 / 2773 | 1.23 / 0.97 | 96740 |
| Transportation LP worker | 0 / 0 | 260 / 164219 | 1.52 / 1.40 | 107580 |
| Boolean LP worker | 10 / 6 | 166 / 2773 | 1.53 / 1.15 | 95856 |

The equality gate stops at the prepared-factor check and consumed LP closure.
The complemented-image gate stops at existing optimum-orbit coverage and consumed
core bounds. These independent negative results admit neither a row-certificate
API nor a signed-image consumer. No ordinary-source gain, default promotion or
mixed-local-search qualification follows; the earlier landscape losses remain
admission constraints. Source coordinates, objectives and conservative query,
scope and unread-column protections retain their existing contracts.
Raw sources, records, preparation captures, hashes and resource receipts remain
outside Git in `klause-evidence/campaigns/cost-symmetry-gates-2026-10-28/`.
Superseded jobs 8874/8875/8876 and the ceiling-only 8904 were cancelled with zero
executed commands. No local compilation, tests or numerical workloads were run.

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

`bench.profile.ProjectedCountProfile` compares component and monolithic exact
projected counting over retained DIMACS sources with disjoint Boolean XOR pairs
and a connected holdout. Options are `pairs=2,4,8 repeats=3 maxChecks=10000`.
Both paths use the same ordinary feasibility preset and 100 decisions per check;
full and x-only projections are separate. Reflection selects the retained internal
monolithic path and fails if its entry point changes. Each measured traversal
includes source parsing/baking, partitioning, component construction, feasibility
search and interval consumption; construction and count time are also separate.
The profile retains original bytes/hashes, exact intervals, current-thread allocated
bytes and installed-build/runtime identity. Alternating repeats follow one warmup
per path and shape. Run it through one bounded lab profile job; instrumented times
and allocations establish no ordinary-source throughput or physical RSS claim.

[Capture 8902](https://lab.eignex.com/jobs/8902), at `e387b8f450`, compares seven
source/projection cases with three alternating repeats on Linux/amd64, JDK
25.0.4.1 and a one-CPU cgroup. All 42 measured traversals return matching native
exact counts: 4, 16 and 256 for the disjoint pairs, and 129 for the connected
holdout. These counts have no independent counting certificate. Original DIMACS
bytes/hashes, installed-build identity, runtime and resource receipts are retained.

For eight pairs, full-projection median total time is 4.08 ms versus 260.63 ms
monolithic, with 3.76 MB versus 120.09 MB of current-thread allocation. X-only
medians are 2.03 ms versus 84.79 ms and 1.71 MB versus 42.30 MB. Negatives remain:
one two-pair full repeat takes 69.93 ms versus 7.88 ms, and one four-pair x-only
repeat takes 66.41 ms versus 10.41 ms. The connected fallback adds 6–38 kB of
allocation per paired repeat. JFR and recorded cgroup throttling make these
diagnostic timings, not a throughput claim. The bounded result supports Boolean
clause products; it supplies no wider factor admission. The lab retried one lost
upload automatically; these observations use its committed second attempt.

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

`TaskOrderProfile` captures native unary and single-pair reified-order controls
before selecting a task-order consumer. The free-pair and equal-start controls
compare minimum, midpoint, impact probing, VSIDS and LP with default/aggressive preparation;
the order overlay adds two existing reified rows and their covering clause.
Each solve has 256 decisions and five seconds for construction and search.
Prefix records retain both order polarities and undo; bounded selector traces,
learning, preparation and thread allocation records include ordinary solver cleanup.
Thread allocations are cumulative JVM allocations, not peak memory; these serial
instrumented controls supply no throughput comparison or independent proof check.
Optional `suite`, `problem`, `modelSha256` and `dataSha256` arguments add one
MiniZinc source. Its compiler identity, original bytes, FlatZinc and output mapping
are retained. The annotated control preserves the source selectors; separate free
controls inspect default preparation and a first-pair overlay. These satisfaction
projections supply no original MiniZinc objective or proof claim. Optional tasks,
variable/zero durations and shared starts decline the overlay.
`mode=source` runs only the four MiniZinc controls, allowing compiler-capable
placement without repeating retained authored captures.

`SourceStageProfile` accepts `request64=<base64 JSON array>` for stage attribution.
A source request has `suite`, `problem`, decoded-source `sha256`, `engine`
(`default`, `cp`, `ls` or `mixed`) and `exact`. MPS and XCSP3 sources are supported.
Optional `timeout` (1,000–60,000 milliseconds) and `seed` default to ten seconds
and seed 3. It profiles the installed CLI with one active processor, a 3 GiB heap, Serial GC and debug statistics,
then runs the existing JFR phase/caller analyzer. It retains the command, source,
streams and recording. These instrumented runs diagnose stages without a throughput
or source-verdict claim. Four spaced `jcmd Thread.print` snapshots through one third
beyond the requested deadline provide a
second view when JFR sampling is sparse; attach can pause the measured process.
JFR closes 35 seconds after the deadline so a later process kill preserves its
completed recording. A process still alive 50 seconds after the deadline is killed;
that failed capture retains its diagnostics and establishes no source verdict.
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
18 `{suite, problem, sha256}` catalog identities, with at most 96 KiB of encoded
request and 1 MiB per original source. It requires the frozen original hash before
parsing and retains those bytes. Submit it as a Lab profile job without host or
machine placement overrides. The Lab's documented `profile.runTimeoutSec` covers
active setup, compilation, execution and cleanup; `base.timeout` supplies the
separate command allowance.
`requestz64` accepts the same JSON after gzip compression and Base64 encoding, with
the same 96 KiB encoded ceiling and a 1 MiB expanded JSON ceiling. `request.json`
retains expanded bytes and settings name the encoding; source hashes and query
semantics are identical across the two envelopes.
The measured child uses one active processor, Serial GC and a 3 GiB heap, retaining
its command, streams, actual exit, installed CLI/harness/runtime hashes and options.

A request entry may carry `gzip64`, a gzip-compressed Base64 copy of its frozen
source. This bypasses catalog retrieval and verifies the same required original
SHA-256 before parsing. Decoding retains the 1 MiB source limit and the whole
request retains its 96 KiB limit. Use bytes from a separately identified source
record; supplied suite/problem labels alone do not establish catalog provenance.
The settings distinguish supplied bytes from catalog retrieval. This input changes
no query, condition, preparation or ordinary CLI semantics.

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

`capture=true` retains bounded exact nonlinear failure observations in direct or
prepared traces. `capture=both` also runs the same query without capture and writes
`plain-trace.json`; completed deterministic controls compare outcomes. The unique
irrational-root unit control also compares exact witnesses. Charged observation can
change work-based cursor arbitration and optional-probe trajectories. `maximumWork=N` sets one containing native allowance per query.
Captured work includes observation and can reach that allowance sooner. Compare
native work and component duration separately; neither establishes CLI speedup.

Capture keeps the first rejected probe, first rejected CAD prefix and first
completed sign conflict, exact
normalized/active predicates, signed regional premises, condition bounds, numeric
reverse fibres, source coordinate names, snapshot identity and the latest operation
with its projection operands. The first representation refusal keeps its own
operation/support record across later attempts. Algebraic coordinates retain defining rational
coefficients, root index and isolating endpoints. Each copied record part permits
512 data entries and 16,384 key/value characters before metadata and field prefixes;
a polynomial permits 128 terms, each
monomial 64 coordinates, and coefficients 4,096 bits. Incomplete parts say
`complete=false`; an interrupted copy sets `InterruptedCapture`. Repetition compares
at most 256 exact representations, with `RepetitionComplete=false` beyond that;
it does not decide equality of independently represented algebraic numbers.
Each capture attaches before query entry and belongs to one query. Interleaved
root/search cursors and suspended partial-sign checks restore their
own operation and support records before resuming arithmetic. Copies poll and
charge the containing query. `CompletedCopySteps` counts completed
serialization steps, excluding other observation costs; interruption can charge a
final uncompleted step. Paired native totals retain all costs and any changed search
trajectory. No observation authorizes exclusion.

Request entries may include `fixedIntegers={"name":"integer"}` to measure a raw
fixed-integer real completion. Every original integer declaration requires a fixing;
decimal integer strings permit at most 256 digits. These conditions retain their
original coordinate map and lower/upper equality pins. Prepared traces use their
own model coordinate namespace. `originalSourceCoordinates` retains original names
when available; `coordinateMapping` explicitly reports that original-to-prepared
correspondence is unavailable. Prepared slot identifiers cannot be interpreted as
original source slots. Ordinary `pipeline=true|both` runs retain their separate
source-admission/publication scope and supply no capture of those worker queries.

`fixedBooleans={"gate":false}` on a request entry selects an explicit original
Boolean condition in direct or prepared diagnostics. Prepared conditions require
the captured plan's source identity and its declared Boolean coordinate map;
`conditionBooleans` records the actual query pins. This uses no real-coordinate
correspondence and cannot be combined with pipeline observation. The original
bytes and hash remain unchanged; a conditioned verdict applies only to that branch.

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

### Exact univariate refinement profiles

`PolynomialPhaseProfile refinement=true|both` enables the internal query option;
`both` retains `control-trace.json` with refinement disabled at the identical revision,
source bytes, requested fixing, representation limits and containing allowance.
`cellRefinement` counts candidate coordinates, completed checked cells, repeated
candidates, repaired coordinates, private declines and cumulative optional native
work. The containing native snapshot includes refinement and complete fallback.
The optional path shares 16,384 units across snapshot replacements, admits degree 16,
64-term predicates with 4,096-bit coefficients, and retains at most eight cells.
Seeds and regional support have a 64-coordinate/premise ceiling. A missing cell or
candidate is a bounded decline, not a source verdict.

`checkedCells` serializes the completed typed facts after the query: numeric predicates,
exact algebraic endpoints, closure and source/query/regional scope. Source support
means a mandatory predicate of the original unconditioned polynomial formula.
Conditional records retain all query bounds/Booleans; regional records also retain
signed premise IDs and process-local snapshot/reduction identities. Those identities
are not persistent source mappings. Source/query cells require an active-coordinate
map or surviving-predicate match when a reduced CAD sample contains eliminated
placeholder slots. Serialized diagnostics are not a replay or
independent proof format. Source checking, native UNSAT, component candidate entry,
projection counts, total cost and physical resource receipts remain separate claims.

Original-source witness checking runs through an ordinary CLI experiment at a pinned
activation revision, using the existing source reconstruction, model rendering and Lab
checkers. Component traces keep their native verdict scope; prepared slot arrays have
no original-source correspondence in this diagnostic.

### Regional checked-cell feedback profiles

`PolynomialPhaseProfile refinement=true feedback=true|both` selects private numeric
feedback with the same checked producer. `both` adds `feedback-control-trace.json`
with feedback disabled and refinement enabled at the identical revision and settings.
The feedback option requires `refinement=true`; it cannot silently compare different
producers. `cellFeedback` records admissions, lookups, completed uses, repeated uses,
uses under distinct regional owners, evictions, snapshot replacements and scope declines.
`MetadataVisits` counts attempted metadata visits, not total feedback work; every visit
polls/charges the caller, and exact endpoint comparisons also enter the containing
native and optional refinement work. Compare total costs, actual avoided evaluations
and region progress alongside outcomes.

`CandidateUses`, `CandidateWork` and `CandidateDeclines` isolate numeric atom checks
that consult the view outside the producer allowance. Their separate cumulative
8,192-unit allowance still charges the containing query and survives snapshot changes.
Regional scope copies and replacement cleanup remain separately charged to the caller.
These checks may reject a required positive atom at the same point and position as
its ordinary evaluation; they supply no family or Boolean exclusion. Private stops
decline the hint and retain the original evaluation. Source/query facts must match
the active predicate and scope; regional facts retain their original producer owner.

`firstCellFeedbackReuse` retains the first observed use across regional owners,
including its checked predicate/cell, signed regional premises, numeric conditions
and process-local source/snapshot/reduction identities. Scope previews cap condition
maps and wide coefficients and mark incomplete rendering. The engine retains the
exact guards; these diagnostic strings supply no proof or persistent source mapping.
An origin is an observed producing regional context or the first successful regional
lookup; importing a bank fact does not assign it a regional origin.
An absent trace establishes no cross-region reuse, even when lookup counts are nonzero.
`firstCellFeedbackUse` also records reuse within one regional owner. Both traces keep
the actual rejected numeric candidate's defining coefficients, root and isolating
bounds, with an explicit completeness flag for bounded rendering.
Retained trace scalars admit degree sixteen and 4,096-bit coefficients and isolating
bounds, with optional image provenance removed. Larger values keep the scoped cell
metadata and mark the numeric preview incomplete, without retaining their candidate graph.
`queryElapsedNs` and `queryThreadAllocatedBytes` cover query construction through its
terminal return, including reflection and in-loop diagnostic rendering. They exclude
source parsing/preparation and final trace serialization. Thread allocation is absent
when unsupported; it measures cumulative Java allocation rather than retained or peak
physical memory. These instrumented, ordered arms do not establish warmed throughput.

Matched profiles at `ba5b2b7c35` use the merged checked producer, identical hash-pinned
sources and settings, and feedback as the only per-arm option. All 54 pairs match
native outcomes and caller charges balance. The 48 completed pairs match projection,
lifted-cell, regional and partial-sign-exclusion counts. Six deadline pairs retain
their different search frontiers and are excluded from completed-work comparisons.

| Context | Pairs | Native SAT / UNSAT / stopped per arm | Uses / numeric atom uses / cross-owner uses | Completed work delta |
| --- | ---: | --- | --- | ---: |
| [Unrelated-prefix controls 8676](https://lab.eignex.com/jobs/8676) | 16 | 10 / 5 / 1 | 68 / 48 / 28 | +81,620 |
| [Whole-family holdouts 8677](https://lab.eignex.com/jobs/8677) | 17 | 12 / 5 / 0 | 10 / 10 / 0 | +4,189 |
| [Raw originals 8678](https://lab.eignex.com/jobs/8678) | 9 | 3 / 4 / 2 | 0 / 0 / 0 | +115 |
| [Prepared contexts 8679](https://lab.eignex.com/jobs/8679) | 12 | 5 / 4 / 3 | 8 / 8 / 0 | +12,128 |

In the degree-two, four-prefix-pair UNSAT control, a mandatory source predicate
`x1² = 2` supplies the checked open cell `(-sqrt(2), sqrt(2))`. Candidate zero is
rejected under distinct regional owners and unrelated Boolean premises, with the
same source, condition and snapshot. Twelve numeric atom checks consume retained
cells, nine across owners, while the producer reaches its 16,384-unit allowance.
Both arms visit sixteen regions, project 368 polynomials and lift 2,512 cells with
zero partial-sign conflicts or exclusions. The hint avoids the same atom evaluation;
it creates neither a Boolean clause nor a new certificate or candidate.

The strict irrational-boundary holdout saves 54 charged units, but aggregate cost
increases in every context. Prepared Economics consumes eight cells and preserves
native SAT while adding 2,294 units; its ordered query allocations are 16,670,856
versus 13,381,016 bytes, and elapsed times are 216,774,623 versus 104,457,428 ns.
All four jobs retain one-core, 4,608-MiB hard resource receipts with no OOM events.
These instrumented observations establish bounded numeric reuse, without supporting
default activation or a throughput improvement. Both private options remain off.
Strict, disequal, singular and degree-decline SAT alternatives retain their outcomes.
Component SAT assignments lack independent source-check receipts; native UNSAT is
not an independent certificate, and prepared arrays supply no original-source map.

### Coupled section refutation profiles

`PolynomialPhaseProfile coupled=true|both` enables the internal coupled-section option
in the direct or prepared query; `both` adds `coupled-control-trace.json` with that
option disabled at the identical revision, source bytes and refinement/feedback
settings. It cannot be combined with another `both` comparison. `coupledSections`
counts attempted supports, evaluated root combinations, conflicts, already attempted
supports, private declines and optional work, which also enters the containing native
work. The progress checkpoints add consumed coupled exclusions and their reuses.
`firstCoupledConflictPreview` renders the first refuted support's signed predicates,
capped at sixteen premises with a completeness flag. Compare region, projection and
lifting counts and total work alongside outcomes; supports and combinations alone are
not efficacy.

`coupled=matrix` also saves `single-trace.json` with both support preparation and
joint checking disabled, `prepared-support-trace.json` with preparation alone, and
`joint-trace.json` with joint checking alone. The main trace enables both; the
coupled control disables the whole option. These controls use the same sources,
limits and sign checker. `PreparedSupports` and `JointSupports` count attempts,
not consumed deductions. Explanations preview active encoded premises in their
current snapshot; prepared identifiers supply no original-coordinate mapping.

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

`bench.profile.ChannelViewApplicability` accepts `request64=BASE64_JSON` containing
one to eight `{suite, problem, sha256}` records for XCSP3 sources. It reports the
source hint kinds, admitted source definition nodes, retained nodes when value or
predicate channels become searched, and bounded native inverse-role applicability.
Each source has a thirty-second allowance; cancellation and original bytes remain
with the report. This source-only screen does not stand in for mapped working-model
applicability or paired-state grading. Inverse-role experiments transpose only
disjoint native `Inverse` factors, at most sixteen relations of at most 64 entries,
retaining every other factor, source domain and implied-factor role.
Optional `source0=BASE64` through `source7=BASE64` supply the corresponding original
source bytes directly, at most 96 KiB encoded each, checked against the requested hashes.

`bench.profile.LocalSearchTrajectory` takes `suite=ID problem=NAME sha256=HEX
recipe=LABEL seed=N moves=N` for a hash-pinned minimizing XCSP3 source. It compares
protected and substituted objective coordinates through mixed-route preparation,
then runs the selected local-search recipe alone with a fixed allowance counting
moves and restarts, and no shared incumbents. The seed is the worker seed,
including its portfolio index.
Optional `channel=SearchedValues|SearchedPredicates|SearchedChannels|InverseAssignment`
compares the maintained view with one bounded channel treatment. Both arms retain
objective coordinates and the same functional objective view. Value treatments
leave Boolean-value and Element outputs searched; predicate treatments leave
comparison and membership outputs searched. Other admitted definitions remain
maintained. Inverse assignment transposes the roles of admitted native `Inverse`
factors and reuses their repairs and swaps; it changes proposals while retaining
both source arrays. Channel comparisons use local-search-route preparation and
also admit satisfaction sources.
No general inverse or deterministic extension of an inconsistent channel is supplied.
Channel probes prioritize named source integer inputs to the maintained definition
network before other admissible primitives, within the same 32-candidate bound.
Optional `presolve=off` holds both views before presolve, distinguishing channel
ownership from representation changes that erase or reconstruct its coordinates.
The default preserves production preparation; explicit centers also disable presolve.
Optional `source64=BASE64` supplies at most 96 KiB of base64-encoded original bytes
without catalog extraction; the source mode and bytes are retained with the capture.
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
against the unobserved repetition when both complete without deadline censoring.
An unavailable unobserved endpoint supplies no parity claim. Native interval
censoring is latched before post-run source checking and serialization.
`traceCoordinates=1..8192` sets the capture coordinate limit (default 4096).
At most 32 repair/descent attempts retain their
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
Scopes of at most six coordinates also enumerate every nonempty proper subset,
so completed controls can distinguish a coordinated barrier from missed smaller
freedoms. `recipe=cbls-extended/fixed` adds the existing extended structured source
to the catalog's fixed plateau recipe for comparison with richer move controls.
`selectors=true` requires an explicit center and compares the opt-in semantic
selector with Random, WorstObjective, AdjacencyRelated and eligible source-input
samples over sixteen seeds. All arms use the same center, ordinary CP owner,
10,000 decisions per repair and a shared fifteen-second allowance. Existing
selectors receive the semantic block's source count as their nominal target;
definition closure and root-fixed exclusions can change the effective count,
which is recorded rather than clipped. Eligible samples match source counts on
the definition-free controls. Selector/closure visits, physical selection and
repair allocations/times, and shared setup cost remain separate. Ordered cold
captures establish capability and costs, without a throughput claim.
The capture reports native original-source checks separately from independent
witness checking and carries no unpinned proof claim. `center=v0,v1,...` supplies
an integer-only source center; `center64=BASE64_JSON` carries the complete
`{bools:[...],ints:[...]}` source assignment, bounded to 512 KiB encoded. Explicit
centers disable presolve and use the protected objective. The shared center
projection follows both namespaces through the prepared map, refuses conflicting
aliases, unmapped searched coordinates, domain/pin mismatches and changed complete
source round trips after definition reconciliation. Native source and working
feasibility checks precede the warm start. The first observed state must retain
that source center; random initialization and greedy replacement are bypassed.
Channel arms replay the same supplied center with fresh weights, tabu,
configuration and worker RNG. Implicit-seeding recipes decline explicit-center
replay because that seeding can replace the center and establish different owners.
`timeoutMillis=100..60000` contains preparation and
each walk; `moves` retains the ordinary containing move/restart cap. Mapping and
checking cost and deadline censoring stay explicit.
`recipe=source-coordinate-descent`
selects the strict objective-coordinate control; ordinary catalog recipes retain
their structured sources. Fixture expectations live beside
[finite repair controls](smoke-corpus/finite-repair/README.md).
`batch64=<base64 JSON array of argument arrays>` captures up to eight requests
in numbered directories under one profile job.
`batchGzip64` accepts the same JSON compressed with gzip, bounded to 96 KiB encoded
and 8 MiB inflated, for complete-center matrices that exceed native argument limits.
Decoding remains outside individual walk allowances and inside full-process costs.
`ownerReplay=true` consumes those exact `controls=true` records after capture and
compares LS, cold/retained CP and cold/retained mixed completion. Every owner uses
the saved center, effective working freedoms, complement pins and strict exact
center-objective cutoff; real snapshots without exact authority and CP cutoffs
outside its exact binary64 contract are unavailable. No domain is invented and
mixed admission retains all rows or declines the overlap. Each owner sequence has
100,000 containing `NodeBudget` decision equivalents and ten seconds, with two
seconds and 2,000 decision equivalents per fragment. `ownerWork` and
`ownerFragmentWork` select the containing and per-fragment work allowances;
both mixed sequences share the same cumulative LP work/allocation/check limits.
Looser cutoffs start a fresh retained epoch. Reports separate outcomes, scoped
closure, source acceptance, CP nodes, LS moves, LP work, preparation, thread
allocation and before/after counters. Instrumented CP clause flags count a lower
bound on previously unused retained clauses becoming useful in unit/conflict
propagation, split by permanent status; flags are never cleared, repeated uses of
already marked clauses remain unknown. Diagnostic flag visits and their time and
thread allocation are reported separately from native decision work; elapsed time
and wall-time limits include the observations. A completed native cutoff closure
retains its completion classification when a cooperative allowance also runs out.
These observations supply no global proof or throughput claim.
Common control loading and source-center checking are recorded separately in
`owners-*-preparation.json`; per-candidate original-source verification time and
allocation are separate from each owner's native interval.

[Finite owner replay 8518](https://lab.eignex.com/jobs/8518), at `862c575692`,
consumes the merged capture on Linux/amd64 across assignment, resource and
placement exchange controls, with strict coordinate-descent and strong structured
recipes. All 147 conditions have identical centers, effective freedoms, complement
pins and exact cutoffs across five owners. Each sequence has 100,000 containing
decision equivalents, ten seconds, and 2,000 equivalents/two seconds per fragment;
no fragment overspends. LS and both CP owners find the same fifteen native
source-accepted improvements. LS retains feasible nonimprovements in the other
132 conditions; CP closes those conditioned cutoffs. Mixed declines all native
global shapes rather than dropping a source constraint. Cold CP uses 35 decision
nodes versus 32 retained, without newly useful retained clause flags in these tiny
controls. Retained CP allocates 134.6 million cumulative thread bytes versus
43.2 million cold; LS uses 235,200 moves and 1.067 billion thread bytes.
These ordered, instrumented observations establish neither a retained throughput
gain nor independent witness certification, a global proof or ordinary admission.
The pilots 8479/8489 expose segment-end LS charging and do not qualify per-fragment
owner costs; native instruction caps enforce the work boundary in 8518.

`ownerSelectors=true` with `selectors=true` replays the merged semantic selector's
actual effective freedoms at its declared center through the same owners. The
selector's bounded construction/selection costs stay in the hash-bound
`selectors.jsonl`; owner replay measures conditioned capability separately, with
200,000 containing decision equivalents by default so all eighty selected scopes
can receive their 2,000-equivalent fragment allowance. Declined empty selections
remain unavailable rather than becoming completed fully pinned repairs. Cold
fragments start from the specified owner seed; retained fragments keep RNG and
activity state. These comparisons do not attribute all node differences to clauses
or charge selection costs as owner repair work.

`ownerRecipes=r1,r2` adds an opt-in `ls:<recipe>` inner owner per named trajectory
recipe to every owner replay. Each repair builds a fresh recipe with the acquisition
walk's definition sweep, per-move invariants, implicit-seeding switch, gradient view,
class-normalized weights and linear shaping, then warm-starts at the captured center
under the same pins, cutoff, seed and fragment allowance as the default `ls` owner.
A warm start seeds no implicit owner until a restart. Rows record the internal
compound tally, restarts, slices, maintained-definition and implicit-seed scope
mismatches; pins are never released to admit a compound. Shared hint, sweep and
gradient preparation is reported once in the owner preparation file, outside the
repair allowances. Every checked improvement from any owner then runs the declared
unpinned source-coordinate descent with 128 moves, 2,000 decision equivalents and
two seconds, recorded separately with its native source check. `owners=a,b,...`
selects the owner sequence; the default places structured owners after `ls`.
`ownerSeed=N` reseeds only the owners and their descents, keeping the walk,
selections and captured scopes of `seed`. Recipe `cbls-extended/fixed` names the
trajectory's plateau walk with sampled extended structured moves; `catalog:<label>`
selects the catalog arm itself, such as the implicitly seeded `cbls-extended/fixed`.
Owner replay captures selector sets without a duplicate producer-side CP solve;
`producerRepairEvaluated` distinguishes this capture from selector-only comparisons
that also evaluate their own repairs.
`selectorsFromWalk=true` acquires the center from the bounded walk's actual checked
incumbent, with presolve and objective rewriting disabled. It requires `selectors=true`
and omits an explicit `center`. Selection and owner replay start after both walk
captures, preserving their fixed-work observation comparison. `selector-center.json`
binds the source hash, recipe, immutable working center, native checked witness file
and separate acquisition time/allocation. A missing incumbent, changed coordinate
frame or unsupported relation remains unavailable. Boolean and integer namespaces
both require the saved source-plan mapping and complete source round trip. Completed
descent events stay in `repairs-false.jsonl`; an acquired incumbent alone supplies no
completed-descent claim. These declines concern this diagnostic's supported overlap.
With `selectorsFromWalk=true`, `ownerConflicts=true` also consumes the merged
conflict selector at that same checked center in both source-root and
sampled-fragment acquisition modes. Each mode saves its original acquisition,
selection and descent records beside a separate hash-bound owner replay. The
common repair cutoff belongs to the checked center; acquisition outcomes and costs
remain separate and confer no additional pins or proof authority.
The merged `FiniteConflictSelection` diagnostic accepts `ownerReplay=true` to
replay its actual conflict-supported, baseline and acquisition-ablated scopes
under the same contract. Acquisition costs remain in the hash-bound
`selections.jsonl`; unmatched source counts, missing scopes and unavailable
guidance remain distinct. This conditioned replay uses the exact checked center
cutoff, with the native acquisition threshold retained separately in the selection
record; it does not combine acquisition and repair into a policy comparison.

[Conflict owner replay 8615](https://lab.eignex.com/jobs/8615), at `725db3a1ce`,
consumes 160 actual scopes from source-root and sampled-fragment acquisition on
the weighted Latin, assignment, resource and placement controls. All five owners
share each center, effective freedoms, pins and exact cutoff. The records preserve
148 matched source counts, twelve unmatched sets and nine actual consumptions of
supported conflict guidance, alongside the acquisition-ablated controls. LS and
both CP owners find the same 39 native source-accepted gains; the other 121 scopes
retain feasible nonimprovements for LS and closed cutoffs for CP. No fragment
overspends. Cold CP uses 140 nodes and 110.2 million cumulative thread bytes;
retained CP uses 88 nodes and 292.9 million bytes, without newly useful retained
clause flags. Mixed declines every global shape. Acquisition and descent costs
remain in the original selection records, outside the owner allowance. These
instrumented controls establish no composite policy or throughput advantage.

[Semantic owner replay 8579](https://lab.eignex.com/jobs/8579), at `02d362ad1f`,
consumes 640 actual selector scopes across three- and four-input assignment,
resource and placement controls, with owner seeds 7 and 19. All five owners share
the center, effective freedoms, pins and exact cutoff in every scope; 603 scopes
match the selector's source-input count and the remaining 37 retain their actual
larger or smaller sets. LS and both CP owners find the same 177 native
source-accepted improvements. The other 463 scopes yield feasible nonimprovements
for LS and closed conditioned cutoffs for CP; mixed declines every global shape.
No fragment overspends its 2,000 decision equivalents. Cold CP uses 500 nodes and
233.2 million cumulative thread bytes; retained CP uses 359 nodes and 940.8 million
bytes, without newly useful retained clause flags. LS uses 1,024,000 moves and
2.666 billion thread bytes. Selector construction/selection costs remain outside
those owner measurements. These tiny controls support neither a repair-quality
advantage nor a retained throughput or ordinary admission claim; the strong outer
walk recipe is separate from the replay's default LS owner.

[Initial family capture 8624](https://lab.eignex.com/jobs/8624), at `284cafd9b6`,
retains eight native source-checked walk centers and 329 matched owner scopes from
the six centers its diagnostic admits. CarpetCutting's 48 Boolean auxiliaries
trigger the integer-only capture guard; this is a harness limitation, not an owner
inapplicability result. Producer-side CP solves consume the selection deadline on
ClockTriplet before all selector arms are captured. Clause-flag scans also charge
the native work allowance and relabel 68 completed retained-CP queries as budget
stops. Those records retain `nativeReason=SearchExhausted`; they earn no retained
quality or cost conclusion. The capture requires Boolean-frame support and
separate diagnostic accounting before qualifying these families' repair owners.

[Ordinary family holdouts 8616](https://lab.eignex.com/jobs/8616), at `725db3a1ce`,
complete 48 cases on three original XCSP3 COP sources and the three exchange
controls, with 30 seconds, P1 and seeds 7/19. Every final witness passes the
independent XCSP3 SolutionChecker against its original source hash. Objectives
below retain both seeds in order:

| Source | Default | CP | Curated LS | Retained-CP ALNS |
| --- | ---: | ---: | ---: | ---: |
| ClockTriplet-15-20_c22 | 161 / 161 | 162 / 162 | 161 / 161 | 163 / 164 |
| RCPSP-j120-01-01_c22 | 112 / 111 | 110 / 111 | 547 / 500 | 662 / 638 |
| CarpetCutting-test01_c23 | 638 / 640 | 627 / 643 | 622 / 718 | 630 / 641 |

All engines reach zero on the exchange controls. Default and CP report twelve
native optimum labels there; the independent checker certifies witnesses, not
proofs. No original holdout receives an optimum label. The whole-engine results
retain the scheduling losses and seed variation; they support no unconditional
owner replacement. They measure ordinary execution separately from identical-scope
repair and do not establish a retained throughput advantage.

Model keys, domains, objectives, source bytes and
build identity remain alongside the walk. Inputs admit at most 64 MiB,
8,192 Boolean/integer coordinates and 8,192 factors. Hint construction uses the production
mapping through reflection and fails on an internal shape mismatch. Paired loading
uses the same retained-coordinate projection, leaving reconstructed columns to
the existing source reconstruction. Size and unsupported projection refusals remain
explicit diagnostic declines. Each arm has
a sixty-second outer deadline; cancellation is recorded. These instrumented walks
isolate local trajectories; they establish neither default portfolio equivalence,
throughput, native proof nor independently checked source feasibility.

`bench.profile.ObjectiveViewComparison` uses the same hash-pinned source and paired
probe API for retained linear, exactly substituted linear and retained functional
objectives. It accepts `suite`, `problem`, `sha256`, optional `recipe`, worker `seed`,
`moves`, `timeoutMillis`, `greedy`, and `screen=true` for a parse-only capability receipt.
`retainedWorker=true` adds the production canonical-graded geometric consumer,
installed through search initialization rather than the observation source.
`preparationEngine=mixed` selects default finite presolve; `preparationEngine=ls`
selects the source-preserving local-search presolve policy used by sole-arm CLI runs.
Each view runs alone; preparation counts against its time allowance. Up to six
initial/post-greedy source boundaries supply at most 32 common admissible primitive
moves per view pair, with functional input coordinates sampled first. Complete
source round trips and identical reconstructed destinations determine correspondence;
equal seeds or recipes do not establish proposal equivalence. When a definition
network maintains every objective cone, retained linear and functional objectives
provide a grading control while exposing changed objective hotspot coordinates.
Private pairings use each view's captured initial class weights; native walk
histories remain separate.
Preparation and thread allocations, native incumbent source-expression values,
RNG boundaries, raw/weighted/objective deltas and model keys remain separate.
Censored walks retain their last observed native incumbent separately from a terminal verdict.
`basins=true` adds the existing native repair trace, bounded to 32 attempts and
4,096 working coordinates. Its source-projected frames and declared integer-input distances separate
policy local optima, feasible stalls, restarts and censored attempts; they do not
classify a whole source basin or certify a partition. Trace copies consume the walk
allowance, while source projection and serialization run after the walk.
Private initialization/probe diagnostics run outside the walk allowance. Walk time
includes passive recording overhead; ordinary CLI lab runs with source validation
supply independent witness checks and whole-process resource measurements.
Reserved CLI cohorts use `reference=any` on their problem selections to retain
members without qualifying reference results. Native first-feasibility timestamps
and independently checked final endpoints remain distinct observations.
Both runners accept `source64` or `sourceGzip64` containing the original source,
bounded to 96 KiB encoded and 64 MiB inflated, with the original hash mandatory.
The objective runner also accepts the same bounded `batch64` request form.
Both runners accept the JVM property `-Dklause.bench.trajectory.compress=true` to
gzip every JSONL file after the complete capture. Filenames gain `.gz`; decompressed
records retain their original content. Compression time and allocation are reported
in `compression.json` outside the walk allowance and remain part of the full process
cost. Uncompressed output is the default.
It accepts the shared `center64` projection for common-center replay and
`geometry=true` adds four canonical-grading arms: term/leaf hotspots crossed with
adjacent/geometric leaf steps. Unsupported or incompletely maintained cones
decline. The ordinary sink retains pins, definitions and ownership; no source
relation or feasible objective order changes. `proposals.json` separates raw
templates from sink-emitted moves (channel expansion can add moves), and retains
up to 32 complete candidate destinations, exact canonical/source values and native
source checks. Every distinct emitted seed move at that center receives a complete
value comparison under a ten-second allowance; disagreements or incomplete scans
decline geometry walks. Domain clamping can make raw cone values differ despite
live maintenance. Complete candidate value agreement and the structural cone check
remain distinct from global equivalence inferred from sampled grades. Walk counts,
native incumbents, time and allocation remain the efficacy observations; proposal
counts alone establish no improvement. Private proposal screening runs outside
the walk allowance.

The common-center [proposal matrix](https://lab.eignex.com/jobs/8753) at
`f55a01792b` replays the first retained cost-zero policy-local-optimum centers from
[GBACP](https://lab.eignex.com/jobs/8660) and the whole-family
[TestScheduling holdout](https://lab.eignex.com/jobs/8661), seeds 3/7. Source hashes,
complete centers and runtime/distribution hashes remain in the captures. All seven
views use `cbls-plateau/ils-basin`, fresh learned state, protected source coordinates,
5,000 containing moves/restarts and five seconds including preparation/mapping.
The Linux/amd64 profiles request a 2 GiB heap, SerialGC and one active processor.
All 7,564 distinct within-view seed-candidate comparisons agree on complete exact
canonical, cone and source values; all 28 final points pass the native original-source
check. These checks supply no independent XCSP3 certification or global equivalence.
Sixteen walks are clock-censored. The retained/substituted controls finish all
5,000 moves at 691/546 on GBACP and 6205/5249 on TestScheduling. Geometric proposals
reach fewer moves within the deadline, so their endpoint differences do not isolate
a matched-work effect or establish a speedup.

The [matched-work follow-up](https://lab.eignex.com/jobs/8762) at `36055880b8`
holds the same sources, centers, seeds, recipe and proposal axes at 1,000 containing
moves/restarts with twenty seconds including preparation/mapping. All 28 walks
complete uncensored and pass native source checking. Each pair of hotspot choices
has the same complete source endpoint, and canonical geometric leaf proposals
reproduce the full functional treatment's complete endpoint in all four cases.
Exact minimization endpoints use seed order 3/7:

| Source | Center | Retained/substituted | Adjacent leaf steps | Geometric leaf steps/full functional |
| --- | --- | --- | --- | --- |
| GBACP-UD04 | 1774 / 1624 | 834 / 1121 | 759 / 758 | 687 / 685 |
| TestScheduling-t020m10r03-1 | 8025 / 6132 | 7541 / 5727 | 7895 / 5708 | 6674 / 5708 |

This cohort supports leaf step generation as the useful proposal mechanism, with
no independent hotspot benefit observed. The first 32 retained seed candidates
include one native source-accepted adjacent improvement per TestScheduling seed;
GBACP has none in that bounded sample. Full seed scans qualify values, not native
feasibility of every candidate. Geometric GBACP walks cost 11.5–12.0 seconds and
1.26–1.28 GB of calling-thread allocation versus 0.65–1.93 seconds and 33–37 MB
for retained/substituted controls. TestScheduling geometric walks cost 1.45–2.38
seconds and 204–205 MB versus 0.29–0.99 seconds and 29–33 MB. These ordered,
instrumented walk costs retain preparation (8–497 ms, 2.5–20.8 MB), mapping/native
checking (1–91 ms, 1.4–8.5 MB) and private seed probing (0.2–602 ms, 0.2–120.2 MB)
separately and support no throughput or default-admission claim. The stronger
five-second/5,000-move controls remain part of the comparison.
Follow-up compression retains all JSONL bytes, reducing 121.6 MB to 4.1 MB after
capture at a separate 1.56-second cost; it changes no walk allowance.

Retained-worker comparisons select `param.objective-guidance=canonical`,
`functional` or `geometric` within an explicit LS recipe pool. With
`param.arm=cbls-plateau/ils-basin`,
`param.arms=2` and `param.objective-guidance=functional,geometric`, two private
retained slots share the containing budget. Compare against `functional,functional`
at the same total budget to preserve ordinary slot seed offsets and restart
diversity. `lsGeometricProposalRuns`, `lsGeometricProposalDeclines` and
`lsGeometricProposalCandidates` distinguish qualified consumption from canonical
fallback; candidate offers alone establish no useful transition. `param.ls-phase-times=true`
retains active preparation and search cost. Use lab resource measurements and
source validation for whole-process cost and independent final-witness checks;
native source checks and heuristic terminals supply no proof authority.

The retained-consumer [replay 8803](https://lab.eignex.com/jobs/8803), at
`b5510ecde5`, reuses 8762's original hashes, complete checked centers, seeds 3/7,
recipe, 1,000 moves/restarts and twenty-second containing walk allowances. The
production geometric policy initializes through the engine, using the prepared
cones and live network. All sixteen walks complete uncensored and their endpoints
pass native original-source checks; these captures have no independent XCSP3
certification. Exact endpoints use seed order 3/7:

| Source | Center | Canonical | Full functional | Retained geometric |
| --- | --- | --- | --- | --- |
| GBACP-UD04 | 1774 / 1624 | 834 / 1121 | 687 / 685 | 543 / 689 |
| TestScheduling-t020m10r03-1 | 8025 / 6132 | 7541 / 5727 | 6674 / 5708 | 7541 / 6085 |

Geometric GBACP improves one functional endpoint and loses the other; both
TestScheduling endpoints lose to functional guidance, and seed 7 also loses to
canonical guidance. Calling-thread walk allocation is 106–109 MB for geometric
GBACP versus 1.29–1.30 GB functional and 35–37 MB canonical. TestScheduling uses
90–110 MB versus 218 MB functional and 30–33 MB canonical. Ordered, instrumented
walk times are 1.14–1.57 / 9.64–11.15 / 0.79–1.80 seconds on GBACP and
0.77–0.86 / 1.51–3.61 / 0.39–1.46 seconds on TestScheduling, respectively.
These are changed trajectories, without a general throughput or memory claim.

Geometric preparation costs 20–206 ms and 2.6–12.0 MB; checked center mapping costs
2.7–14.7 ms and 1.4–4.0 MB. Initial observation costs 8–54 ms and 1.0–2.1 MB;
private seed probing costs 0.27–1.17 ms and 0.23–0.32 MB. Final native checks cost
2–8 ms outside the walk timer. The full capture command takes 50.5 seconds,
including loading, all views, probes, validation, handle closure and serialization;
compression accounts for 0.735 seconds and 1.91 MB outside walk allowances.
The Linux/amd64 JVM uses a 2 GiB heap, Serial GC and one active processor, as
recorded in its command; inherited CLI provenance options describe a separate
3 GiB CLI configuration. Runtime, compiler and distribution hashes stay in the
capture. The preparation/upload-inclusive deadline failure in
[8790](https://lab.eignex.com/jobs/8790), its spent-allowance retry and the
pre-execution cancellation of 8788 supply no efficacy evidence.

The whole-CLI [consumer matrix 8789](https://lab.eignex.com/jobs/8789), at
`7ec9a79a17`, fixes seven source identities, seeds 3/7, P1 and ten-second
containing budgets. GBACP and TestScheduling are mechanism-informed controls;
the other five selected families are frozen consumer holdouts, separate from
fresh default admission. C, F and G denote canonical, full-functional and geometric
guidance with `cbls-plateau/ils-basin`. Pairs are actual retained two-slot races
under the same total budget, including ordinary slot seed offsets and restarts;
F+F and C+C supply seed-diversity controls. Ordinary mixed retains its complete
workers and default source policy. All 112 cases finish without a case failure.
Original hashes, compiler, distribution and runtime fingerprints match across
arms on one Mac/aarch64 runtime, with a 3 GiB heap, Serial GC, one active processor
and debug enabled. All 93 final witnesses pass the independent XCSP3 checker.
The nineteen missing points remain unresolved. Values are exact minimization
endpoints in seed order 3/7; `p` is a native optimum label, without independent
proof certification.

| Source | C | F | G | C+C | F+F | F+G | C+G | Ordinary mixed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| RCPSP-j120-01-01_c22 | 505 / 539 | 508 / 539 | 505 / 539 | 522 / 530 | 520 / 529 | 521 / 529 | 522 / 529 | 114 / 110 |
| Warehouse-60-120-01_c22 | 40403 / 44624 | 40403 / 43764 | 38924 / 42318 | 43515 / 44755 | 44791 / 44534 | 38412 / 43794 | 42766 / 48366 | 20859 / 18312 |
| GBACP-UD04_c23 | 615 / 542 | 615 / 687 | 469 / 469 | 615 / 542 | 618 / 616 | 469 / 469 | 471 / 469 | 689 / 618 |
| FlexibleJobshopScen-dh-5-16-005_c25 | 4024 / none | 4075 / none | 4024 / none | 3424 / 3696 | 3453 / 3365 | 3669 / 3731 | 3644 / 3617 | 1535 / 1403 |
| TestScheduling-t020m10r03-1_c24 | 4954 / 4893 | 4972 / 4546 | 5711 / 5040 | 4951 / 4237 | 4916 / 4339 | 4802 / 4749 | 4954 / 3796 | 1876 (p) / 1876 (p) |
| BinPacking2-n1c1w1a_c24 | 26 / 26 | 26 / 26 | 26 / 26 | 26 / 26 | 26 / 26 | 26 / 26 | 26 / 26 | 25 (p) / 26 |
| NurseRostering-10_c22 | none / none | none / none | none / none | none / none | none / none | none / none | none / none | none / none |

Only GBACP and TestScheduling qualify geometric proposals: twelve requested cases
emit candidates after live-cone qualification. The other thirty geometric cases
decline to canonical execution; their deadline-dependent differences supply no
geometric-view evidence or family-wide inapplicability result. NurseRostering
supplies no point in any arm, and the three single-worker FlexibleJobshop seed-7
runs also supply none. These unavailable outcomes are retained.

GBACP's single geometric and F+G workers reach checked 469 in both seeds, beating
all canonical, existing functional, equal-budget seed-diversity and ordinary mixed
controls. TestScheduling's single geometric worker loses both functional endpoint
comparisons. F+G beats F+F at seed 3 and loses at seed 7; C+G improves seed 7 over
C+C but loses seed 3. Ordinary mixed proves TestScheduling's 1876 optimum in both
seeds and BinPacking2's 25 optimum in seed 3; the LS alternatives retain neither
those proof outcomes nor their quality.

Whole-CLI resource receipts bind by SHA-256 to all 112 solve records. They include
solver preparation, native validation, search and closure; parent bench/corpus
work and independent checker time remain separate. Final source checking costs
106–387 ms per witness. Native first-feasibility timestamps are scalar observations;
only the final complete instantiations receive independent checking.
On GBACP, geometric and functional workers both take 10.12/10.11 seconds; peak RSS
is 240384/222544 versus 242368/239024 KiB, and user CPU time is 13.08/11.79 versus
13.31/12.61 seconds. Mac CPU reservations permit JIT bursts. F+G takes
10.12/10.10 seconds and 251184/243632 KiB, versus F+F's 10.21/10.10 seconds and
232896/241600 KiB: the composition retains its RSS increase. On TestScheduling,
single geometric takes 10.12/10.12 seconds versus functional 10.12/10.11 seconds;
RSS is 232240/232560 versus 232656/230992 KiB. Ordinary mixed completes its two
proofs in 0.76/0.66 seconds at 205520/206432 KiB. Process RSS and calling-thread
allocation have different scopes; neither the replay nor this deadline matrix
establishes a general speed or memory improvement.

These checked GBACP transitions and bounded consumption qualify an explicit
research consumer using one prepared model. Root-start discovery and checked-center
improvement remain separate observations. The TestScheduling losses, absent
holdout capabilities and stronger ordinary proofs preserve default routing; the
[held-out admission](#held-out-objective-guidance-admission) rejects a default.

The frozen worker-seed 3/7 captures at `12be285415` and `356449b93a` retain
[mixed preparation](https://lab.eignex.com/jobs/8531),
[local-search preparation](https://lab.eignex.com/jobs/8581), and
[reserved scheduling](https://lab.eignex.com/jobs/8593) controls. Together with the
bounded Warehouse and other reserved-source captures, 2,176 paired candidate
observations have matching complete source destinations and equal raw, weighted
and objective deltas. These sampled controls establish no grading diversity.
Prepared functional objectives are available on GBACP-UD04 and
TestScheduling-t020m10r03-1: integer hotspot sets change from 216 terms to 139 leaves
and from one output to 20 leaves respectively, while native endpoints and costs
differ. TestScheduling's functional walks allocate 1.66–1.78 GB on the calling
thread versus 0.22–0.29 GB for retained linear walks; these ordered, instrumented
captures measure neither whole-process memory nor a general speedup.
CarpetCutting points decline the complete round trip. Warehouse-60-120-01 and
NurseRostering-10 exceed the capture limits; size-selected Warehouse-40-80-00 fits
and supplies equal-grade retained/substituted controls. Those refusals give no
family-wide eligibility conclusion. The [frozen Cargo default control](https://lab.eignex.com/jobs/8576)
returns UNKNOWN in all three 30-second Linux runs; it emits no checked witness.
Native profile endpoints supply no independent source-witness or portfolio evidence.

The ordinary [reserved comparison](https://lab.eignex.com/jobs/8642) fixes
`8a850f3c2f`, sole-arm LS, the existing `cbls-plateau/ils-basin` recipe, normalized
class weights and lambda 1. Discovery families are CarpetCutting, RCPSP,
SchedulingOS, Warehouse and GBACP; Cargo, FlexibleJobshopScen, TestScheduling,
BinPacking2 and NurseRostering were reserved as whole holdout families before
selection. Cargo stays in its separate frozen default control. The other nine
original sources each receive three views, seeds 3/7/11, a 10,000-move allowance
with a 30-second deadline, and a separate ten-second allowance. Preparation consumes
the containing budget. All 162 cases complete: 138 feasible endpoints pass the
independent original-source XCSP3 checker, six FlexibleJobshopScen seed-7 cases
and all 18 NurseRostering cases return UNKNOWN. No outcome carries a proof.
Every original source hash matches its reservation; all 162 external resource
receipts bind by SHA-256 to their native solve records. Infrastructure failures
before the accepted recovery supply no solver observations.

Functional-objective availability is true only for GBACP and TestScheduling in
this cohort. The other seven requested functional treatments fall back to raw
linear grading and supply no functional-view evidence. Retained and substituted
views have equal feasibility and exact endpoints in all 27 work-limited groups;
four time-limited groups differ, without an isolated grading-cause claim. Exact
checked minimization endpoints below use seed order 3 / 7 / 11. Retained and
substituted endpoints coincide for both displayed sources.

| Source | Allowance | Retained/substituted | Functional |
| --- | --- | --- | --- |
| GBACP-UD04 | 10,000 moves | 687 / 543 / 974 | 614 / 686 / 685, wall-censored |
| GBACP-UD04 | 10 seconds | 615 / 544 / 833 | 615 / 762 / 759 |
| TestScheduling-t020m10r03-1 | 10,000 moves | 5060 / 4893 / 5042 | 4944 / 4546 / 4857 |
| TestScheduling-t020m10r03-1 | 10 seconds | 4954 / 4893 / 4758 | 5090 / 4546 / 4891 |

GBACP functional runs reach only 3,745 / 1,515 / 3,818 moves at the deadline;
their endpoints are not matched-10,000-move gains. All TestScheduling work runs
complete 10,000 moves, but whole-CLI wall time rises from 5.71 / 4.38 / 1.53
seconds to 17.69 / 15.17 / 7.53 seconds, and CPU time from 5.61 / 4.26 / 3.21
to 17.50 / 14.97 / 9.77 seconds. Peak RSS is 151100 / 139340 / 232608 KiB
versus 133220 / 132604 / 233248 KiB, so allocation growth in the profiles does
not imply uniformly higher process memory. GBACP work-run RSS is
229232 / 133704 / 216336 KiB versus 238320 / 159076 / 238960 KiB.
Preparation is similar within each source/host: TestScheduling retained
330 / 294 / 70 ms versus functional 374 / 296 / 71 ms; GBACP retained
160 / 625 / 157 ms versus functional 171 / 678 / 159 ms. Hint construction
is common to the treatments before the diagnostic gradient filter.

Native attribution preserves scalar feasible-objective trajectories and first
feasibility timestamps, while only the final full source instantiation is emitted
for independent checking. For example, CarpetCutting retained/work seed 3 reports
first native feasibility at 33 ms, first process-relative feasibility at 310 ms,
and finishes the CLI at 613 ms with checked objective 1028. CLI completion bounds
the first available independently checkable witness; the earlier scalar points
have no independent first-feasibility validation. Whole-process receipts exclude
the parent bench and independent checker. The JVM uses `-Xmx3g`, Serial GC and
`ActiveProcessorCount=1`; advisory Mac CPU allowances permit JIT bursts. Every
source/seed/allowance view triplet shares a host, with Linux and Mac strata kept
distinct. These observations support within-triplet comparisons, not pooled
hardware throughput or portfolio claims.

The source-projected endpoint captures at `d1648fa6e9`,
[GBACP](https://lab.eignex.com/jobs/8660) and
[reserved TestScheduling](https://lab.eignex.com/jobs/8661), use seeds 3/7 and the
same bounded profile recipe with `basins=true`. Their 768 matched candidates,
combined with the earlier 2,176, give 2,944 equal-grade observations at identical
complete source destinations. Retained and substituted models in these four
captures have identical prepared linear objectives and factor descriptors, and
identical source trace prefixes; they supply a coalesced representation control.
The 367 recorded attempts contain 365 native policy local optima and two censored
functional GBACP attempts; another 1,629 attempts exceed the trace cap and are
declined. Trace copies read 2,767,023 coordinates. Distinct declared-input
endpoints are 25/30 retained versus 15/16 functional for GBACP, and 29/31 versus
32/27 for TestScheduling. No retained/functional endpoint is shared within these
bounded prefixes. Integer-input displacement ranges are 0–112 / 5–114 versus
0–92 / 0–97 for GBACP, and 0–22 / 1–21 versus 1–22 / 1–15 for TestScheduling.
These are policy-qualified endpoints under changed proposal sets, not exhaustive
source basins, independently checked profile witnesses or grading diversity.

Retain the existing maintained functional-cone view as a capability-gated
complementary research candidate: exact closure and complete source correspondence
admit different searched coordinates, source endpoints and checked quality.
TestScheduling improves all three work endpoints but loses two of three clock
comparisons; GBACP has one clock win, one tie and one loss with severe work-run
censoring. Reject a general substitution-diversity or default/portfolio benefit
claim. The evidence supports no wider admission, Cargo adjustment or new
neighborhood, and the diagnostic CLI treatment used by the study is not a
production selector.

### Held-out objective-guidance admission

The admission protocol was frozen at `d9f127f275` before any outcome. It selects 16
xcsp3-cop minimization families unexposed to the representation studies, by objective
text alone: functional expressions (CyclicBandwidth, Filters, LargeScaleScheduling,
LowAutocorrelation, NursingWorkload, SREFLP, ButtonsScissors, PSP2) or sums over
declared auxiliaries (CVRP, KMedian, VRP, TSPTW1, TSPTW2, PSP1, MultiAgentPathFinding,
IHTC). Each family contributes the first two members ordered by `sha256("3621:<name>")`.
GBACP-UD04, TestScheduling-t020m10r03-1, Warehouse-40-80-00 and Cargo-09-1s-18 are
reported controls. [8824](https://lab.eignex.com/jobs/8824) runs `f638e5004d`, default
mixed, P1, 30 seconds, source validation and seeds 3/7/11. Arms are ordinary default,
single `functional`/`canonical`/`geometric` guidance with
`arm=cbls-plateau/ils-basin`, the `functional,geometric` composition and the
`functional,functional` seed control. Every guidance arm replaces the curated LS pool,
as the explicit-pool contract requires.

All 648 cases run on Linux/amd64, with the six arms of each problem/seed group on one
host, and every source hash matches the frozen draw. The 263 points all pass the
independent XCSP3 checker; 372 cases return UNKNOWN. Thirteen
LargeScaleScheduling-03200 cases, ordinary ones included, are killed at about 80
seconds without a record ([#3722](https://github.com/Eignex/klause/issues/3722)). No
holdout arm loses a native proof; TestScheduling proves 1876 in every case. Ordinary
Cargo returns no point, as in [8576](https://lab.eignex.com/jobs/8576); only the
composition reaches one, 49497 at seed 3.

Live-cone qualification succeeds on ButtonsScissors, CyclicBandwidth, Filters, IHTC,
LowAutocorrelation, MultiAgentPathFinding and NursingWorkload, and declines on the
other holdout families. ButtonsScissors, MultiAgentPathFinding and NursingWorkload,
like PSP1 and both TSPTW families, have no point in any arm. A declining slot runs
canonical guidance, so a decline is not neutral against `functional,functional`.
Net wins minus losses over member/seed pairs, where a checked point beats none, for
the qualified families with points and GBACP:

| Family | Composition vs ordinary | Composition vs seed control | Geometric vs functional | Functional vs ordinary |
| --- | --- | --- | --- | --- |
| CyclicBandwidth | +1 | +2 | +2 | -1 |
| Filters | 0 | +3 | +2 | -3 |
| IHTC | +3 | +2 | -1 | +3 |
| LowAutocorrelation | +1 | +1 | +3 | -2 |
| GBACP (control) | -1 | +3 | +1 | -1 |

The geometric slot supplies the final checked incumbent in all three LowAutocorrelation
and GBACP composition runs and two IHTC runs, mostly after a functional-slot first
witness. These are improvement-phase transitions, so retirement at the first witness
would discard them. On qualified families, composition median whole-process CPU
ratios are 0.998–1.000 against both controls at the deadline, and peak RSS ratios
against ordinary are 0.97–1.08.

The frozen rule rejects default admission. The composition beats both ordinary and seed
controls in three qualified families and loses in none, at matched cost, but loses five
checked holdout points against ordinary: all three SREFLP-sko049-1 seeds, CVRP-A-n38-k5
seed 11 and VRP_LC-09-05-10-s1 seed 11. The explicit functional control loses the same
SREFLP points, so the loss comes from replacing the curated pool on models where the
view declines. Objective guidance remains an explicit research option and functional
guidance the ordinary default. A qualifying rule would leave the curated pool intact
where cones decline, fall back to functional guidance, and need families this cohort
has not exposed.

### Finite conflict selection

`bench.profile.FiniteConflictSelection` takes `suite=ID problem=NAME sha256=HEX
center=v0,v1,... seed=N` for a hash-pinned XCSP3 optimization source with an explicit
feasible center. It admits an unchanged integer coordinate frame with every coordinate
named in the source and no frontend-declared definitions, at most 1,024 coordinates
and 4,096 factors. The source bytes, request and build identity accompany the records.
A `batch64` JSON array of argument arrays compares several pinned controls in one
lab profile. The [weighted Latin control](smoke-corpus/finite-repair-evidence/README.md)
has an exact feasible center and lower-bound witness.
`acquisition=source-root|sampled-fragment` selects a full-source prefix or a
count-matched random repair prefix under explicit complement pins. The fragment
draw uses `seed + 1`; final selector ties use `seed`. Both acquisition arms retain
their actual origin pins and share the same prefix.
`semantic=Assignment|Resource|Placement` adds the corresponding
`SemanticBlockRemoval` control from the selected revision. It retains the complete
returned packet, including fixed role coordinates, and matches counts using only
movable source inputs. A size mismatch records a decline without repair; packets
are neither truncated nor retried to meet the target. Construction and closure
consume the same containing deadline, with visit and allocation costs retained.

Random, objective and adjacency removal compare with CP-conflict-supported removal
at the same center and requested count, using 2/3/4/8 movable inputs where admitted.
A separate acquisition-ablated random arm
pays for the same 128-decision CP prefix as the guided arm. Acquisition, a repair
of at most 1,000 decisions and a descent of at most 128 moves share one 2,000-unit
native search allowance and a five-second deadline; capture, selection and source
checks consume that deadline. Root-fixed coordinates are
excluded, and effective definition-closed freedom counts must match before repair.
Records retain captured support weights, unavailable/empty outcomes, acquisition
points, repaired objectives, native original-source checks, work, allocation and
separate acquisition/selection clocks. The shared repair trace records the first
qualified source-coordinate greedy descent endpoint with the declared tie seed;
candidates use explicit feasible greedy acceptance, and censored descents remain
unclassified. Source input distance and checked objective
after descent stay separate from repair acceptance. These instrumented controls
establish no independent witness check, global proof, basin classification or
ordinary portfolio throughput.

`numerical=true` adds captured relaxation-point disagreement, producer-projected refutation
support and numerical-acquisition-ablated random arms. These admit the unchanged
integer frame only when its complete affine source context is available.
`numerical-cuts=true|false` selects the acquisition's existing cut policy. Each
numerical acquisition advances the real source authority once; context preparation,
the strict query-local objective cutoff, capture, projection and selection debit
one containing source funding ledger. Objective cutoffs retain the supplied source
direction and units. The same acquisition's recovered incumbent is source-checked
and separately credited; its pre-recovery point remains relaxation guidance.
Integral complete points also receive the original-source finite check; fractional
or incomplete integer points are explicitly not source witnesses.

Numerical comparisons give every arm a 2,000,000-unit containing ceiling, the sum
of native search units and modeled source work, with the same five-second deadline,
1,000-decision repair and 128-move descent limits. Both work components, source
allocation, physical time and thread allocation remain separate; their units do
not imply equal CPU cost. Numerical owners close within acquisition accounting.
The existing bounded numerical journal retains scopes, points, masks, supports and
native evidence checks. Whole-packet/count mismatches and unavailable feedback
remain explicit. Linear [fixture controls](smoke-corpus/finite-repair-evidence/README.md)
separate coordinated freedoms, fractional guidance and independent recovery.

The `numerical-disagreement-completed` and `numerical-refutation-completed` arms
consume the same acquisition through source-row completion. They take one draw at
the requested count; a request smaller than every complete packet declines without
repair rather than truncating or retrying. Records keep each seed's packet outcome,
planned values, the row that added every member, the consumed packet inputs and the
ranked fill count. Each consumed packet member also gets a proper-subset control:
the same effective freedom without that member, repaired from the same center under
its own 1,000-decision allowance and the shared deadline. Those controls are not
charged to the arm; an exhausted subset without improvement shows that the removed
input was required at that center.

### Conditional component traces

`bench.profile.ConditionalComponentTrace` takes repeatable `source=SUITE:PROBLEM:SHA256`
and optional `searchMillis` (10,000), `traceMillis` (15,000), `prepareMillis` (60,000)
`seed` (3), `probes` (3) and `probeMillis` (2,000). Two self-authored two-five-cycle WCNF controls run first: the bridge
`s or x(0) or y(0)` as stated (optimum 6), then with `s` hard-true (optimum 7, two
independent cycles). Each hash-checked source goes through finite preparation with the
CLI's presolve allowance, then `satOptimized` minimization. A wrapping variable selector
samples every branch point for 4,096 picks, then every sixteenth, until the trace
allowance is spent; sampling time is excluded from the search deadline. A sample joins
the free coordinates of every live factor: a clause with a true literal is discharged
whatever its literal order (`dischargedAfterFreeLiteral` counts those whose true literal
follows a free one), and every other factor, including clauses with atom literals, connects all its free
coordinates. The objective is treated as additive, so splits are an upper bound for a
decomposition that must also respect an incumbent cutoff. A component's key hashes each
live factor's residual in factor order; a key seen again after an intervening retraction,
conflict or restart counts as a re-encountered child obligation. Every eighth sample
reports source-only and all-enforcing incidence, including full learned PB support,
weights, degree and established values. Discharged restrictions add no incidence.
The observation admits at most one million terms and polls the containing trace deadline;
unsupported learned kinds, unnameable atoms or interruption withhold completeness.
Permanent marks retention only; logical scope and cutoff provenance are unavailable
from that mark. Entry counters count observations, including repeat observations of
the same constraint, and store positions apply only to that observation.
Up to `probes` split
nodes keep their residual clauses; after the search each component and the joint residual
are solved as fresh `satOptimized` models, and the joint residual twice more, with
`LpConfig(LpEmphasis.DEFAULT)` as the ordinary-bounds control and with `objectiveGuidedValues`
as the ordering control. The children share one allowance equal to the
joint solve's, carry no incumbent cutoff or learned clause, and an unfinished child leaves
the composition open. The composed witness is reconstructed and checked against the
original clauses, and later sightings of each probed key are reported. Records retain
source hashes, preparation outcome, the solve result, and native original-clause checks
of the reconstructed witness; non-clause factors are counted as unchecked. A trace
establishes residual structure only, not reuse savings or portfolio throughput.

Boolean-clause split probes retain `<sample>.residual.json` beneath the source's
snapshot directory. `snapshotBytes` bounds total serialized retention per source
(8 MiB by default); `probes` bounds count, up to 16. Capture and incumbent observation
charge the trace clock. Serialization has a conservative size preflight and a final
byte check; interruption, unsupported models/restrictions and exhausted retention
decline explicitly. Snapshot storage time is reported separately.
The record uses `ProblemJson` for the prepared model and supported learned clauses/PBs,
with exact coordinate maps, signed objective, constant, established values, fixed and
source-isolated contributions, source/preparation identities, opening assumptions,
an incumbent witness and its exact strict cutoff, and source/enforcing membership.
All retained learned restrictions stay conditioned on the captured state; permanence
does not grant broader authority. Preparation reconstruction is unavailable offline,
so snapshots explicitly withhold original-source replay.

`bench.profile.ConditionalComponentReplay` takes `snapshot64=BASE64` or
`snapshotFile=PATH`, plus `sha256=HEX`. `snapshotJob=ID` resolves a file beneath a
retained local lab job; a missing artifact fails rather than retracing the source.
Loading performs deterministic structural verification without solving. It recomputes
residuals, objective contributions, maps, condition identities and membership; hashes
identify bytes and establish no proof. `replay=true` separately invokes the same child,
joint, objective-ordered and ordinary LP controls, with `probeMillis` (2,000) per arm
and `seed` (3). Children share one arm allowance including model construction.
Source-relaxation and all-enforcing replay retain distinct scopes; the latter keeps
every supported learned restriction and the captured strict cutoff. Native residual
witness checks provide no original-source verdict or independent optimality certificate.
Raw snapshots belong in `klause-evidence/campaigns/`, outside Git. An opt-in
`ConditionalComponentTrace.run` consumer receives `ResidualProbeContext`, retaining
the in-process reconstruction route for separately bounded policy experiments.

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

### Objective core projection and completed-query replay

[8917](https://lab.eignex.com/jobs/8917) runs 48 core and 24 ordinary BnB controls
at `4f90f3e94b`, over two self-authored CNF/linear-objective pairs, retained/default
preparation and three repeats. Every result is native optimal 100 with an accepted
source point. Stratification handles the unequal-weight control in one two-input
core; ordinary OLL takes two cores, first with three inputs. For eight unrelated
equal-weight terms, preparation retains the ten-input core, stratification does
not shrink it, and no cost pin is root-satisfied. Bounded propagation deletion
reduces the core to two inputs and search nodes from nine to one, but allocates
about 39 KiB extra and supplies no stable total-time gain. The existing helper's
`minimizeCore=true` retains all ten seed-prefix pins. The configured default LP
control executes no LP passes, so it is an applicability decline.

[8926](https://lab.eignex.com/jobs/8926) confirms in-place seed-proof projection
at `2ac7fe5453` on that equal-weight source, with both preparation policies and
one repeat. Actual query traces retain the unrelated cheap pins while OLL consumes
the two-input core and reaches checked objective 100 in one node. The prepared
projected handle allocates 223,568 bytes, versus 233,304 for ordinary OLL and
271,496 for deletion. Timing includes a 57 ms projected outlier; this single
confirmation establishes no speedup. Explicitly forced LP bounding also certifies
100 and closes at zero nodes. Seed projection remains an internal, default-disabled
bounded capability; these controls justify no automatic admission change.

[8918](https://lab.eignex.com/jobs/8918) captures six two-query sequences at
`4f90f3e94b`. An incidental source-valid `q` learned before the first completed
refutation survives a fresh source check and is replayed after the OLL transition.
Later search nodes fall from six to five, with checked source objective 1. In the
two non-warmup retained repeats, verification alone costs 1.54–2.03 ms, against
0.92–0.95 ms for rebuilding/search without replay; replay adds construction and
allocation. [8927](https://lab.eignex.com/jobs/8927) confirms the completed-query
sequence at `2ac7fe5453`: the later `q=false` assertion count falls from one to zero,
with unchanged checked source outcomes. A conditioned `q or a` negative rejects
the unit obtained under `a=false`. Prepared-coordinate identity deliberately
declines, so its zero assertion counters in the older captures are unavailable,
not evidence that preparation discharged `q`. No production replay cache or mutable
query owner is admitted: total replay cost exceeds the avoided obligation.

These are instrumented native controls on one Linux/amd64 vCPU, a 4,608 MiB cgroup
and JDK 25.0.4.1. Installed harness/runtime hashes, resource receipts and source
bytes are retained with the jobs. In 8917/8926, the historical `sourceSha256` field
hashes the CNF concatenated with its coefficient CSV; source and objective byte
hashes are distinct fields in the maintained profile. Native refutations are not
independently proof-checked. No default-route or whole-CLI throughput gain is claimed.

### Conditional component composition

[Conditional component traces](#conditional-component-traces) cover the eleven
WCNF sources of status sweep [8846](https://lab.eignex.com/jobs/8846) at `364582ebf`,
all feasible and unproven at ten seconds, plus the two-five-cycle controls. Every
prepared factor is a clause, so no enforcing source relation is outside the trace. Jobs
[8852](https://lab.eignex.com/jobs/8852), [8856](https://lab.eignex.com/jobs/8856) and
[8858](https://lab.eignex.com/jobs/8858) ran at `f6dc3eca`, `7a315f07` and `891e11ae`
with a trace that joined a clause's free literals before reading a later true literal,
so their split statistics undercount independence; their compositions, witnesses and
refutations are unaffected. Job [8862](https://lab.eignex.com/jobs/8862) at `2b1b6867`
repeats 8856 with order-independent membership. At `846b8184`, jobs
[8868](https://lab.eignex.com/jobs/8868) and [8892](https://lab.eignex.com/jobs/8892) add
the objective-guided joint control on the five sources with closed compositions, with a
20-second trace allowance. [8867](https://lab.eignex.com/jobs/8867) ran on a host three
times slower and spent its 6-second allowance before the p06 split region, and
[8872](https://lab.eignex.com/jobs/8872) timed out fetching the corpus before any source. All ran on one Linux/amd64 vCPU with 4.6 GiB and JDK 25.

The conditioned control splits at its root into the two cycles. Children of 3 and 3 plus
the fixed separator compose to 7 in 20 nodes, against 82 for the joint residual. Across
the sources, every closed node composition that a joint residual also closes equals its
optimum, every composed witness passes the original clauses, and child and joint
refutations agree in every refuted probe: four in 8856, five in 8862.

The correction changes one source. Preference-planning p06 splits from decision level
10,600: its largest component holds 44–62% of live clauses instead of 99.8%. Two probed
nodes split into 575 and 576 components whose children all close at zero residual cost
in at most 2,209 nodes, while the joint and LP-bounded joint residuals stop at two
seconds. The composed witnesses, 1,312 and 1,322, pass the original clauses and lie below
the traced incumbent of 2,969–3,234. With `objectiveGuidedValues` the joint residual
closes the same nodes in 2,139 and 1,985 nodes (81 and 39 ms), and it refutes the third
probed node in 144 nodes against 1,264 for its children. The surviving work is a
value-ordering cost that existing ordering removes, not independence.

The other sources keep their pattern. Protein never splits. On privilege, tcp, CSG,
haplotyping and packup a giant component holds the residual: split-off children carry at
most 12.4% of free variables, or the giant stops without a verdict. A p03 node closes its
children where the joint stops only on the faster 8856 host. There its 26,707-clause giant
child, beside a six-clause one, finishes within the allowance; on later hosts every child
solve and joint control stops. On metro, rna and p03 the ordered joint residual closes
every node the children close in less time than the children's total. The one gain that survives
every joint control is a shallow auctions node, where 161 clauses beside a nine-clause child
solve in 31,810 nodes and 124 ms against 96,302 nodes and 170 ms ordered. A full residual
scan costs 4–220 search nodes per sample. Learned-clause merges count clauses only, not
learned pseudo-Boolean entries such as the objective cutoff, so they bound no
cutoff-inclusive connectivity. Probed keys are re-encountered after retractions, but a
re-encountered key is not consumed reuse.

On the corrected trace, no nontrivial independent obligation survives preparation, LP
bounds, learning and ordering under containing allowances on the retained scope, so
neither traversal nor cache integration follows from it. Raw records are under
`klause-evidence/campaigns/conditional-components-3724/`.

### Integer cost cores

Lazy integer cost frontiers for a directly selected `core-guided` worker were compared
in job [8869](https://lab.eignex.com/jobs/8869) at `e180dc3d4b` against mixed defaults
and `lp=aggressive`: P1/20s, seeds 3/17, Linux/amd64, debug enabled, independent XCSP3
witness checks. On `xcsp3-core/pair-cover-costs` every arm proves 6; the core arm
returns two two-literal cores with floors 3 and 6 in 2 nodes, against 20 nodes for
the others. A Boolean threshold view of the same source reaches those floors with a
five-literal second core, because its seed prefix includes the eager suffix selectors.

On Wordpress-04-10-500_c24, whose reference optimum is 1442, defaults and aggressive LP
reach 1442 unproven with root bound 0. The core arm raises an exact floor to 343 and
394 after 199 and 250 cores, but finds no witness. On FAPP-aux-01-0200_c25 two cores
reach 8,930,251 against a best known 99,955,504, and no arm finds a witness. Both
MiniZinc candidates failed to load in every arm. These floors survive ordinary
preparation but close neither gap and cost the incumbent, so curated pools keep
Boolean-only admission. Raw records are under
`klause-evidence/campaigns/finite-cost-core-avenue/lazy-3738/`.

### Residual scope capture and replay

[Capture 8903](https://lab.eignex.com/jobs/8903), at `09b97f920b` on Mac/aarch64,
retains the conditioned two-cycle root in 2,256 bytes. The case times out before an
auctions record, so it supplies no ordinary-source applicability result.
[Replay 8923](https://lab.eignex.com/jobs/8923), at `d1a6e116c0` on cloud Linux/amd64,
loads those exact bytes without fetching a corpus or retracing search. Both use
JDK 25.0.4.1. Re-encoding and reloading preserve every byte and the residual meaning.
The two source components close at 3 each, plus the fixed separator cost 1; joint,
objective-ordered and ordinary LP controls agree at 7. The retained incumbent has
strict cutoff 7, which joins both cycles into one enforcing component. Every enforcing
control refutes that cutoff-conditioned workload, while the source relaxation remains
feasible. Native prepared witnesses are checked; original-source reconstruction and
independent optimality certificates are unavailable offline. This establishes replay
and scope fidelity, with no portfolio or reuse-speed claim. Runtime/resource receipts,
hashes, snapshots and the timed-out source attempt remain under
`klause-evidence/campaigns/residual-snapshots-3740-3741/`.

### Retained objective alias costs

[8895](https://lab.eignex.com/jobs/8895) compares `d4e3c9b659` with the matched
expansion-disabled `6d8a789c68` on the pair-cover alias and replacement-objective
controls: P1/5s, seeds 3/17, one case per seed and arm. Ordinary mixed and aggressive
LP/probing controls retain their existing preparation. All sixteen cases attain
native optimum 6, and every original-source witness passes the independent XCSP3
checker. The native proofs are not independently certified. Runtime, non-solver
dependencies and source hashes match on Linux/amd64, JDK 25.0.4.1+1-LTS, with a
3 GiB Serial-GC heap and one active processor per CLI. Four concurrent cases share
a hard four-vCPU/16,896-MiB cgroup.

Normal preparation retains the protected sum output. Its private separable view
consumes two two-term cores instead of six singleton output cores, reaching floor 6
with propagation work 898/752 instead of 2,205/2,221. The already exposed objective
retains two cores and 544/547 propagation work in both builds. Retaining the alias
also preserves ordinary-search structure: ordinary nodes are 7/2 with the alias and
17/17 after objective replacement; aggressive LP/probing uses 5/11 and 19/18.
These are consumed capability and representation controls, without a verdict gain.

Whole-CLI external wall seconds for the alias core change from 1.50/1.42 to
1.67/1.29; peak RSS KiB changes from 124,608/125,060 to 124,012/122,448. The opposite
timing directions, cold startup, concurrent execution and single observation per
seed establish no speedup. Allocations were not collected. The bounded directly
selected capability retains original objective authority and ordinary admission;
no broader-source or default-route benefit is established. Sources, hashes, runtime
identities, checker results and resource receipts remain outside Git in
`klause-evidence/campaigns/objective-alias-costs-3745/`.

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

### Global and extensional scoring comparisons

`bench.profile.GlobalScoringComparison` consumes the paired source-state capture
with `sets=global-scoring-core-v1,global-scoring-holdout-v1`, optional `seeds`,
`moves`, `millis` and `captures`. Defaults are seeds 3/7/11,
2,000 moves, 2 s per walk and three observations per walk, using `cbls/fixed`.
The core and whole-family holdout sets freeze membership independently.
Alternatively, supply one original source with `suite`, `problem`, decoded-source
`sha256` and `source64`, omitting `sets`. This uses the shared trajectory source
loader: standard Base64 without line breaks, at most 96 KiB encoded, with exact
decoded hashing before parsing. Embedded mode retains the supplied identity and
bytes without rechecking catalog membership. It records `sourceMode=embedded`,
the original XML, request and build/runtime provenance.
Omitting `source64` with the same identity/hash arguments resolves one catalog
source instead. Interrupted walks retain their completed observations and
decline an endpoint while a selected operation may remain private.

The pairwise treatment sums equal present, non-excepted position pairs within
each admitted `AllDifferent`, retaining repeated coordinates, canonical factor
weights, native payload maintenance and native proposals. It admits at most 64
positions and 64 excepted values per factor. Greedy initialization and implicit
seeding are disabled in both walks. This compares one shared weight per source
factor; it does not give separate learned weights to individual disequalities.
Walks normalize weights by factor class and retain allocated learned weights;
paired shadow probes use unit weights. Completed fixed-work endpoints are budgeted
trajectory points, without local-exhaustion or basin classifications.
Other globals, including affine `Distinct`, retain their canonical scores.

Ground tables become tries using only their explicit rows. MDDs become tables
using only accepted paths: at most 64 columns, 65,536 words per input array or output relation, 1,024
accepted paths and 262,144 construction visits. Short tables, nondeterministic
weighted diagrams, overflowing path sums and oversized relations decline without
publishing partial views. Deterministic weighted paths include their summed cost
as a relation column; aliases remain repeated columns. These factor substitutions
are private grading probes and do not change propagation or run alternate walks.

The diagnostic bounds native extensional payload estimates before opening states,
captures at most eight alternate relations per source, and limits captured
grade/coordinate cells to 8,388,608. Every decline remains in the output.
Original source hashes, effective semantics, build/runtime identities, paired
grades and primitive deltas, RNG boundaries, canonical residuals, endpoints,
preparation, native work and available thread allocations accompany the capture.
Fixture captures cover collision ranking, optional/excepted aliases and weighted
cost grading. Captured points have no independent witness or proof authority;
instrumented walk timings do not establish ordinary solver throughput.

At `efc74e907e`, [mandatory and weighted controls](https://lab.eignex.com/jobs/8509)
retain exact source/destination pairing and show changed pairwise collision grades.
For `[0,0,0,1,1]`, native distinctness gives both tested repairs delta -1;
pairwise grading gives -2 and -1. The weighted MDD/table fixture reverses some
adjacent symbol rankings, but the direct cost repair from 2 to 5 is best under
both grades. That fixture supplies no complementary-witness result.

[Overlap MDD](https://lab.eignex.com/jobs/8510),
[overlap tables](https://lab.eignex.com/jobs/8511) and eight admitted
[Eternity tables](https://lab.eignex.com/jobs/8517) retain equal unweighted grades
and primitive deltas at mapped states. Both representations minimize positional
Hamming distance over the same accepted words; this rejects grading diversity
for this unweighted substitution. Each Eternity trie consumes 980 row-cell
visits and 144,160–144,272 thread-allocated bytes; the other 41 relations retain
the capture-limit decline. No domain product is constructed.

The same captures and [BlockedQueens](https://lab.eignex.com/jobs/8512),
[MagicSquare-sum](https://lab.eignex.com/jobs/8513),
[QuasiGroup](https://lab.eignex.com/jobs/8514),
[HyperSudoku](https://lab.eignex.com/jobs/8515) and
[AnotherMagicSquare](https://lab.eignex.com/jobs/8516) compare satisfaction walks
with seeds 3/7/11, 2,000 moves and a four-second deadline per walk. All admitted
walks finish within the deadline. Magic-square-3 reaches native SAT under both
grades, taking 15/8/7 native moves against 15/32/78 pairwise moves. HyperSudoku
reaches native SAT only under native grading, at 1,471 moves for seed 7.
The other walks exhaust the move allowance. Their common unit-weight native
endpoint grades are:

| Source | Native walk, seeds 3/7/11 | Pairwise walk, seeds 3/7/11 |
| --- | --- | --- |
| BlockedQueens | 6 / 4 / 5 | 8 / 4 / 5 |
| MagicSquare-sum | 7 / 7 / 14 | 9 / 14 / 12 |
| QuasiGroup | 83 / 22 / 47 | 26 / 36 / 106 |
| HyperSudoku | 11 / 0 / 14 | 6 / 8 / 9 |
| AnotherMagicSquare | 80 / 145 / 68 | 96 / 120 / 84 |
| Eternity | 31 / 34 / 30 | 40 / 47 / 47 |

These bounded endpoints have no local-exhaustion claim. Changed grades and
trajectories add no native witness here; the reserved families include a lost
HyperSudoku witness and worse Eternity endpoints under the common metric.
Captures retain preparation, moves, allocations and runtime/dependency hashes
on Linux/amd64 with Temurin 25.0.4.1. Their ordered, instrumented timings provide
no ordinary-route speedup or independent source-witness claim.

[WordDesign admission](https://lab.eignex.com/jobs/8526), at `5e403b8b48`, loads
the original catalog source with decoded SHA-256 `341306d8…bcbeda` and declines
before opening states: 15 tables and 105 MDDs imply 130,869,060 private extensional
cells against the 1,048,576-cell diagnostic limit. The remaining factors are 14
lexicographic constraints. This is an explicit payload boundary, without a search
or general inapplicability claim. The source remains in the ordinary cohort.

[Source-checked comparison 8533](https://lab.eignex.com/jobs/8533) retains all ten
frozen sources, seeds 3/7/11 and two repeats. Native and pairwise score-only arms
use `3514ea77f5`, explicit one-arm `cbls/fixed`, greedy/implicit seeding disabled,
LP off and presolve off. Ordinary LS recipes and mixed/default-LP/default-presolve
controls use `133546fd6f`. Each case has P1, 10 s and 2,500 shared work units,
admitting at most 2,000 LS instructions. The ordinary controls retain their
configured composition; this allowance need not exercise every configured arm.
Corpus decoding belongs to selection preparation, and every record retains its
original decoded-source hash. All 240 cases complete after infrastructure retries;
the four killed CLI records remain failures.

| Treatment | Independently valid feasible records | Unknown | Native infeasible | Failed CLI |
| --- | --- | --- | --- | --- |
| Native score only | 14 | 46 | 0 | 0 |
| Pairwise score only | 12 | 48 | 0 | 0 |
| Ordinary LS recipes | 16 | 40 | 0 | 4 |
| Ordinary mixed defaults | 18 | 36 | 6 | 0 |

Every feasible record passes XCSP3-tools SolutionChecker 2.5.1 at `ca58fc79a4`,
checker JAR SHA-256 `388d47f7…038b7`; no feasible record remains unchecked.
The six infeasible mixed results are native proofs on the overlapping-table
control, without independent proof validation. Both score-only arms cover the two
small satisfiable controls; native grading additionally covers HyperSudoku seed 7
in both repeats, while pairwise grading adds no source. Ordinary controls also
recover QuasiGroup, and mixed covers HyperSudoku seed 11.

For HyperSudoku seed 7/repeat 0, native grading produces its checked witness at
818 ms process time after 1,142 moves/1,427 work units; pairwise grading consumes
2,000 moves/2,500 work units without one. Frontend and preparation times are
110/65 ms native and 167/31 ms pairwise. Whole-CLI GNU time reports 0.89 s wall
time for each, 0.77/0.80 s user CPU and 98,032/95,936 KiB peak RSS. These runs use
Temurin 25.0.4.1, `-Xmx3g`, Serial GC and one active processor. Allocation is not
collected by this external timer. The four WordDesign LS failures have exit 137
and associated cgroup OOM receipts under the 4,608 MiB group limit; they are not
unknown solver verdicts or scoring-view gains.

Keep these alternatives in bench tooling. Unweighted Table/MDD substitution
offers no grading distinction; the weighted fixture establishes semantics and
grade differences without a useful checked-search result. Pairwise collision
grading loses checked coverage and adds none against the native and ordinary
controls at this allowance. This supplies no runtime admission prerequisite for
#3620 or #3621. Other weighted grades, fragments and budgets remain untested.

### Maintained and searched channel viewpoints

The bounded channel views retain every relation, source domain and pin. Objective
coordinates are protected and the full functional objective view is fixed across
maintained, searched-value and searched-predicate treatments. Native inverse-role
views transpose disjoint bijections and reuse the existing repairs and swaps.
These capabilities are exposed through diagnostic consumers.

[8527](https://lab.eignex.com/jobs/8527) loads twelve ProgressiveParty states through
the production retained-coordinate projection and compares 384 common destinations
with equal raw and weighted deltas. Its empty working definition network makes it
a no-op control. [8542](https://lab.eignex.com/jobs/8542) holds the initial
MisteryShopper named assignment and RNG boundary fixed with plain CBLS and no greedy
initializer; all 384 common primitive destinations have equal deltas. Implicit
ownership in [8490](https://lab.eignex.com/jobs/8490) admits no primitive candidates,
so that empty sample establishes no grading equivalence.

Warehouse [8553](https://lab.eignex.com/jobs/8553) and
[8554](https://lab.eignex.com/jobs/8554) qualify the 8,192-coordinate bound on a
6,641-coordinate source. Default preparation leaves 3,280 maintained definitions;
the presolve-off value treatment retains 3,240 of 6,520. Each capture admits six
paired states and rejects six searched-origin states by the complete source round
trip. All 192 candidate destinations differ in lowered auxiliaries while their
named source decisions agree. Their grades cannot isolate a common-destination
effect. Reserved NurseRostering-01 captures
[8571](https://lab.eignex.com/jobs/8571) and
[8572](https://lab.eignex.com/jobs/8572) similarly have six paired states, six
round-trip rejections, 191 changed complete destinations and one common destination
with equal raw, weighted and objective deltas. Named decisions agree at all 192
destinations. The walks sample 238 distinct named assignments with maintained
channels versus 239 predicate and 241 value assignments, with no intersection.
Their initial assignments differ despite an equal RNG boundary, so this divergence
does not isolate grading. Paired diagnostics cost 315 and 226 ms respectively,
separate from ordinary solve timings.

The frozen ordinary matrices [8574](https://lab.eignex.com/jobs/8574) and
[8575](https://lab.eignex.com/jobs/8575) run `9cf410f7e8`, P1, seeds 3/17/41,
10,000 or one billion search-work units, and a ten-second deadline in both cases.
Each budget starts a separate CLI process. The discovery cohort is ProgressiveParty,
Warehouse and MisteryShopper; whole reserved families are NurseRostering-01/02 and
two CoveringArray sources. Original hashes, build/runtime fingerprints, resource
receipts and final source checks accompany every case. The ownership matrix has
216 Linux/amd64 and 54 Mac/aarch64 cases; each problem/seed group keeps all eighteen
treatments on one host and build. The inverse matrix is entirely Linux/amd64.
Infrastructure retries preserve accepted cases and supply no solver verdicts.

All 270 ownership cases complete without application errors; all 71 published
points and claimed costs pass the independent XCSP3 checker. Both channel
treatments add no checked feasibility on ProgressiveParty or MisteryShopper. Mixed
solves all three ProgressiveParty seeds at either budget; plain CBLS solves two
work-capped and all three high-work cases. The reserved NurseRostering family has
zero checked points from every LS treatment at either budget, while mixed checks
all twelve cases.

Warehouse-40-80-00 objectives, minimized and ordered by seeds 3/17/41, are:

| View | 10,000 units | One billion units |
| --- | --- | --- |
| Mixed, default | 36716 / 36810 / 43959 | 19024 / 15160 / 12564 |
| Plain CBLS, default | 33540 / 34185 / 34553 | 33398 / 34115 / 34500 |
| Implicit, default | 33440 / 35851 / 34553 | 33406 / 34093 / 34406 |
| Maintained, default | 33397 / 34801 / 34543 | 33349 / 34114 / 34552 |
| Searched values, default | 37235 / 37464 / 38520 | 35811 / 37417 / 36908 |
| Searched predicates, default | 31004 / 33762 / 32854 | 34035 / 32744 / 32833 |
| Maintained, presolve off | 34032 / 33112 / 35754 | 34032 / 34086 / 34571 |
| Searched values, presolve off | unknown / unknown / unknown | unknown / unknown / unknown |
| Searched predicates, presolve off | 32051 / 31430 / 35865 | 33297 / 32297 / 32181 |

Default predicate searching improves checked Warehouse quality in all three
work-capped seeds, including against the unchanged controls. It performs 8,000
moves in 6.29–6.47 s of whole-CLI time; maintained performs 5,208–5,344 moves and
reaches the deadline at 10.29 s. Presolve work is 34.48–34.49 million units, and
peak RSS ranges overlap: 145,772–147,024 KiB maintained versus 146,372–148,876 KiB
predicate. The nominal work cap therefore does not imply equal executed work or
include all preparation. At the high-work deadline, mixed has the best quality in
every seed. Value searching worsens every default Warehouse objective and loses
all six presolve-off witnesses. Initialization and changed moves remain part of
these end-to-end results.

The 126 inverse cases publish 33 independently valid points, all on
CoveringArray-3-04-2-08. Both plain orientations solve every seed at either budget,
but inverse requires 2,341/4,871/2,785 search units versus 720/3,621/678 maintained,
with 15,262,301 presolve units each. Whole-CLI peak RSS ranges overlap
(103.6–112.3 MiB maintained, 103.1–111.2 MiB inverse). Each plain orientation
publishes three distinct source instantiations, with no overlap; this endpoint
diversity does not establish separated attraction basins. Both implicit
orientations solve zero work-capped and all three high-work cases, with work
varying in both directions. Neither orientation solves MisteryShopper. The larger
CoveringArray source has unequal channel arrays and no supported native bijection;
all twelve inverse cases explicitly decline. One MisteryShopper inverse work-capped
case also declines before search statistics and is excluded from work comparisons.
Native first-feasibility times describe discovery; the independent checks certify
each final published point and its claimed cost.

Retain searched predicates as a bounded experimental option for the demonstrated
Warehouse work-capped gain. Reject searched values and inverse roles as uniform
replacements at these allowances. The reserved scheduling result and the stronger
mixed deadline control leave automatic admission unqualified. Combined-channel
captures establish correspondence capability; their ordinary solve quality is
untested. Coordinated repair comparisons require the ownership-aware compound
correspondence recorded in [#3612](https://github.com/Eignex/klause/issues/3612).

The complete-source [center acquisition](https://lab.eignex.com/jobs/8765) at
`96cfcb5ebb` uses `cbls-plateau/ils-basin`, seeds 3/7, 5,000 containing moves/restarts,
five seconds including per-view preparation/mapping, and presolve off. Each
Warehouse-40-80-00 request supplies its first cost-zero policy-local-optimum endpoint,
with all 3,200 Boolean and 3,441 integer source coordinates. ProgressiveParty and
NurseRostering supply only positive-cost restart/censored endpoints in both seeds;
all four whole-family holdout requests remain unavailable. No replacement center
or longer acquisition is used. Acquisition runs on Mac/aarch64; it supplies centers,
not common-center efficacy or timing comparisons.

The [five-second replay](https://lab.eignex.com/jobs/8770) and
[ten-second replay](https://lab.eignex.com/jobs/8771) at `edb8146dd6` run on
Linux/amd64 with a 2 GiB heap, SerialGC and one active processor. Both views accept
the same complete source frames through fresh prepared maps and definition networks,
retain their first observed source point, and start with fresh weights, tabu,
configuration and worker RNG. Original source and runtime/distribution hashes remain
in the captures. Exact minimized objectives use seed order 3/7:

| Allowance | Maintained | Searched predicates |
| --- | --- | --- |
| Saved center | 39348 / 38039 | 39348 / 38039 |
| 5,000 moves, five seconds | 37551 / 27692 | 24156 / 28597 |
| 20,000 moves, ten seconds | 28587 / 19867 | 12012 / 14116 |
| 1,000 moves, twenty seconds | 36399 / 34613 | 34253 / 34085 |

The five-second maintained walks execute only 543/3,430 moves versus 5,000/5,000
predicate moves; ten-second work is 3,794/7,121 versus 18,493/20,000. All maintained
walks and the 18,493-move predicate walk are clock-censored. Every one of these
eight endpoints passes native original-source checking and improves its saved
center. Named source-input changes are 21/83 versus 87/69 at five seconds and
70/93 versus 94/94 at ten seconds; Boolean auxiliary changes remain separate.
These are source transitions, without independent XCSP3 certification or an
exclusive basin claim: the fresh maintained controls also improve the centers.
At five seconds predicates lose seed 7 despite executing more moves. Observed
maintained repetitions are also censored, at 35073/27514; completed predicate
repetitions reproduce 24156/28597. Censored repetitions grant no fixed-work parity.

The [matched-work follow-up](https://lab.eignex.com/jobs/8773) holds the same
centers, source hashes, seeds, recipe, fresh-state policy and runtime at 1,000
containing moves/restarts with twenty seconds. All four walks complete uncensored
and pass native source checking. Maintained/predicate named input changes are
29/28 versus 44/26, and all paired complete destinations differ. Predicate quality
is better in both seeds at equal work; this supports a bounded common-center
transition result without attributing equal grades to different destinations.
Maintained walks take 4.31/2.13 seconds and 950/888 MB of calling-thread allocation;
predicates take 2.41/1.58 seconds and 919/903 MB. Per-view preparation/mapping costs
1.13/0.34 seconds versus 0.42/0.28 seconds and consumes the containing deadline.
Private initialization/probes and post-run native checks remain in full-process
costs. Ordered instrumented captures, the five-second seed-7 loss, allocation
variation and missing holdout centers leave throughput and default admission
unqualified. They do not overturn the uniform value/inverse rejection.


### Cargo objective-coordinate trajectories

`LocalSearchTrajectory` accepts `suite`, `problem`, decoded-source `sha256`,
`recipe`, worker `seed` and `moves`, with optional `greedy`, `sliceMillis` and
`sliceWork`. Optional `source64` supplies at most 96 KiB of base64-encoded original
bytes instead of resolving the catalog archive; the required decoded-source hash
still binds the input. It compares protected and substituted minimizing XCSP3 objectives.
The source is limited to 64 MiB, 8192 finite coordinates and 8192 factors; each
walk has at most one million moves and a shared sixty-second preparation/walk
deadline per representation. `provenance.json`, `request.txt`, unchanged
`source.xml`, `capture-policy.json` and both model descriptions bind the input,
installed distribution/runtime, effective semantics, settings and slot maps.
Ordinary solve records remain authoritative for worker seeds, initialization,
restarts and scheduler allocations.

Passive walk samples copy committed state without candidate probes or random
draws, and never force lazy weight allocation. RNG boundaries retain the concrete
JVM implementation and integer fields. Kotlin XorWow's Weyl addend additionally
identifies underlying 32-bit draws modulo `2^32`; other implementations report
the counter unsupported. Equal seeds and move counts do not isolate randomness.
Greedy entries describe the state before the named shuffled coordinate; paired
captures include initialization, entries 0/49/68/final and the completed pass.

`paired.jsonl` uses private `TrajectoryView` states and `trajectoryPair` for the
objective, channel and global comparisons. Explicit `VarRemap` tables describe
source-coordinate correspondence. Every load must reconstruct every source
Boolean and integer unchanged after definition synchronization; inconsistent
eliminated or maintained outputs report `source-round-trip`, rather than a
repaired counterpart. Open or real-valued sources and transformations without a
retained-coordinate projection are outside this runner. At most twelve source states
each perform two full loads and 32 candidate pairs. Each accepted primitive runs
one ordinary reversible `inspectMove` probe, capturing factor grades, raw/weighted
hard deltas and the selected objective delta; destination equality is reported
separately. Pins, definitions, domains and implicit owners can reject a candidate.
Candidate selection scans source slots, prioritizing the current greedy coordinate,
and keeps common admissible root endpoints and adjacent values without random draws.
An explicit-center repair capture keeps its single protected representation and
reports the paired comparison unavailable.
These bounded diagnostics are additional work, separate from free-walk budgets
and timing; they grant no source witness or proof authority.

[Paired qualification 8435](https://lab.eignex.com/jobs/8435), at `1b8ca8cd5b`,
uses the frozen Cargo source hash, worker seed 6, plateau recipe and 30,000 moves on
Linux/amd64 with JDK `25.0.4.1+1-LTS`. Six substituted-origin boundaries load into
both representations. Of 192 candidate pairs, 188 reach identical complete source
destinations: their raw and objective deltas agree, while 115 weighted deltas differ
under the representations' class-normalized initial weights. Four moves on source
coordinate 109 have different reconstructed destinations and are not grading controls.
All six protected-origin boundaries lack a complete substituted counterpart; the
diagnostic reports that boundary rather than repairing objective aliases silently.
The 384 explicit native probes preserve their committed snapshots and RNG boundaries.
Each paired boundary costs 2.7–27.5 ms under instrumentation, separately from the walks.

The greedy trace retains 43439 versus 46194 at coordinate 109 after entry 49, with
the same random draw boundary. Entry 68 then consumes zero versus sixteen underlying
draws at inactive slot 315. These native observations preserve the historical
before-RNG distinction without treating unmatched infeasible assignments as paired
states. They establish capture capability, not complementary basins, a speedup or
independent source-witness validation. [8422](https://lab.eignex.com/jobs/8422)
exposes the rejected original-domain endpoint sampler; [8416](https://lab.eignex.com/jobs/8416)
fails before parsing because its worker lacks the catalog archive tool and supplies
no trajectory evidence.

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

### Bounded scheduling deadlines

The bounded scheduling deadline controls reuse the original LargeScaleScheduling
03200 and 06400 hashes from [8824](https://lab.eignex.com/jobs/8824).
Ordinary mixed seed 7 on `364582ebfe` in [8849](https://lab.eignex.com/jobs/8849)
publishes UNKNOWN records at 30.7 and 38.5 seconds, without reproducing the kill.
The exact 03200 [stage capture](https://lab.eignex.com/jobs/8850) instead reaches
the 80-second kill: both post-deadline stacks remain in cumulative edge finding
entered by LP rounding. Its failed child, source, streams and JFR remain retained.

With cumulative scan polling at `56a957019b`,
[8853](https://lab.eignex.com/jobs/8853) runs ordinary mixed P1 for 30 seconds over
both sources and seeds 3/7/11 on Linux/amd64. All six hashes match, and all six
publish exit-0 UNKNOWN records and physical resource receipts at 30.44–30.57
seconds. The [matched stage capture](https://lab.eignex.com/jobs/8854) also exits
0 with complete UNKNOWN output. No witness or proof is claimed, so these controls
establish deadline handling without independent witness or proof validation.
Instrumented profiles establish no throughput result; charged scheduling visits
also change the units included in propagation-work totals.

### Cumulative demand-window admission

`bench.profile.CumulativeChoiceProfile` captures ordinary propagation and
off/default/aggressive finite preparation, with a 500-million-work allowance and
20-second cancellation per stage. No arguments select the self-authored FlatZinc
choice control; `suite=ID problem=NAME` compiles an original MiniZinc identity and
preserves its model, data and FlatZinc bytes. The original objective participates
in preparation. Reports retain complete native equality rows, demand domains,
compiler/runtime/build identities, work-budget exhaustion and current-thread
allocation. The compiler must be on the worker's `PATH`; these instrumented
captures establish no throughput result or independently checked source verdict.

On matching control bytes, [baseline 8881](https://lab.eignex.com/jobs/8881)
retains `q=0..1` and `machine=1..3`, while
[candidate 8889](https://lab.eignex.com/jobs/8889), at `b9854a4548`, fixes `q=0`
and removes interior `machine=2`. A baseline failed-choice probe also refutes
that value, and default/aggressive preparation fixes the demand in both arms.
The corrected [control 8922](https://lab.eignex.com/jobs/8922) retains both native
equality rows, including the machine constant column. The control establishes
consumed inference before preparation, without a prepared-source benefit.

Original TestScheduling-20 captures [8931](https://lab.eignex.com/jobs/8931) and
[8908](https://lab.eignex.com/jobs/8908), at `a0f1daf9d7` and `1354c5aca0`, retain
ten constant-duration, 20-task machine cumulatives and their channels. Their
captured demand/channel domains match across all four stages. Both aggressive
stages reach the 500-million-work allowance. Source model/data hashes match;
FlatZinc differs only in generated path comments, with comment-free payload hash
`7a5f6ba081138be4702d0d79fa4a7255ce9cd64524197f4a03d5bfb1ded5d9ef`.

[PR #3768](https://github.com/Eignex/klause/pull/3768) retains the single-identity/seed
[pilot 8936](https://lab.eignex.com/jobs/8936): worse default progress and no aggressive
improvement. Its legacy Gecode completion check is neither assignment-only validation
nor an optimality proof. Compiler declines [8882](https://lab.eignex.com/jobs/8882)/
[8888](https://lab.eignex.com/jobs/8888) and incompatible fixed/LP controls
[8932](https://lab.eignex.com/jobs/8932) supply no search or cost result.

Non-root captures use temporary `CumulativeWindowProfile` implementations at
`c3b7fcb211` and `9ad04bea0f`, original TestScheduling instances, default preparation,
seeds 3/7, 512 decisions and a 20-second safety deadline. Controls disable the pass,
scan exhaustively, sweep completion-ordered energy with suffix minimum slack, or
add zero-load, fixed-profile and global-energy eligibility guards. Hooks attach at
the first selector pick; every control shares initial preparation/root propagation.
These instrumented captures establish no throughput or source-verdict claim.

[8961](https://lab.eignex.com/jobs/8961) and [8962](https://lab.eignex.com/jobs/8962)
retain six/eight strict demand bounds on `2023/test-scheduling/t20m10r10-17` and
five/19 on `2018/test-scheduling/t30m10r3-15`. A recorded channel consumes demand
44's bound to zero: machine variable 5 changes from `1..7` to `2..7`.
[8979](https://lab.eignex.com/jobs/8979)'s guards preserve every completed domain/choice
digest. Each pair below is seeds 3/7; work counts cover native propagation visits.

| Instance | Exhaustive work | Fully guarded work | Pass disabled |
|---|---:|---:|---:|
| 20 tasks | 51.0M / 50.8M | 9.0M / 10.0M | 8.6M / 9.6M |
| 30 tasks | 109.3M / 62.1M | 10.8M / 4.5M | 8.7M / 2.8M |

[8968](https://lab.eignex.com/jobs/8968)'s exact native domain digests differ at
two/six completed picks on 20 tasks and 12/68 on 30 tasks; selected variables and
levels match. [8966](https://lab.eignex.com/jobs/8966) extends the 20-task control
to 4,096 decisions with unchanged nodes/failures and the same six/eight bounds.
Default-LP captures [8957](https://lab.eignex.com/jobs/8957) emit no extra demand
bounds; fixed-profile eligibility [8972](https://lab.eignex.com/jobs/8972) cuts scans
from 3,629 to 26, with 15.29M work versus 15.07M disabled. Native failed-choice
probes at four saved, hash-matched prefixes are retained in
[8996](https://lab.eignex.com/jobs/8996). Each selected demand remains `0..1` under
ordinary propagation; probing 1 refutes that choice and undo restores its parent
domains and level. These are choice-relative native refutations, not source verdicts.

The uninstrumented sweep [8965](https://lab.eignex.com/jobs/8965) fails its 48-case
cost gate; [8977](https://lab.eignex.com/jobs/8977)'s 16 first-fail controls show no
repeatable gain. Final gate [8988](https://lab.eignex.com/jobs/8988) compares original
`48ff493b06`, overflow-only `c183bab38f` and fully guarded `0f3cb352b8` in 56 serial
P1 ten-second resource captures. CP controls use old/safe/candidate/candidate/safe/old
ordering; default/aggressive LP use safe/candidate ABBA, on both instances and seeds.

| Control | Overflow-only baseline | Fully guarded candidate |
|---|---|---|
| 20 tasks, default LP | Reports 1295 in 3/4 runs | Reports 6581 in 4/4 runs |
| 30 tasks, CP only | Reports a witness in 4/4 runs | Reports a witness in 3/4 runs |
| Both, aggressive LP | No candidate objective improvement | Same reported cutoffs |

All 56 captures are uninstrumented with debug statistics enabled. The lab reports
assignment-only MiniZinc checking as unsupported for 55 reported witnesses; one run
has no witness. These objectives are reported values, without independent source
validation or optimality claims. Runtime/resource receipts and original-source hashes
are retained with the jobs.

Admission is declined for the eager, at-most-32-task fixed-duration demand-window
mechanism: observed inference and scan reduction do not offset its total pipeline
cost in these controls. Runtime propagation retains the ordinary passes with
overflow-safe prefix arithmetic. This bounded rejection makes no universal claim
about other subwindow mechanisms or source families.

### Source task-order applicability

[Task-order capture 8898](https://lab.eignex.com/jobs/8898), at `f41e018c2a`,
retains 18 authored controls on Linux/amd64 under a one-CPU, 4,608 MiB cgroup.
Each construction/search has five seconds and 256 decisions, with source checking
recorded separately. The process exits 1 after those controls because that worker
lacks MiniZinc; it establishes no original-source coverage. All nine authored
witnesses pass the native factor checker. Refutations have no independent exported
proof check, and the serial JFR timings establish no throughput comparison.

For two mandatory duration-2 tasks, unary non-overlap requires one order, and both
orders would imply `2 + 2 <= 0`. The existing two reified rows and covering clause
enforce this source-relative partition. Both prefixes propagate bounds across the
whole start range and undo restores the root. The free pair remains a two-node,
zero-conflict solve; prepared reified orders raise propagation work from 22 to
206 and add two Booleans and three factors before preparation. The prepared free
pair also installs the existing difference-system propagator.

On equal starts, raw reified orders produce two conflicts and one learned clause,
versus 31 conflicts for minimum-value search. Impact probing also refutes before
committed decisions. Ordinary preparation maps the native tasks to a shared start,
outside the proposed initial guard; preparing the reified rows refutes at root.
This contradiction therefore supplies no eligible prepared source for a new order
consumer. The capture retains construction, search, check and cumulative thread
allocation costs; allocation is not peak storage or RSS.

[Original-source capture 8935](https://lab.eignex.com/jobs/8935), at `0fa5390dca`,
compiles the hash-pinned `2023/test-scheduling/t20m10r10-17` source with MiniZinc
2.10.1 on cloud Linux/amd64 and exits 0. It retains the compiler executable,
standard-library, solver-library and configuration hashes, original bytes,
FlatZinc and output mapping. Its four controls use the same decision/time caps
and one-CPU, 4,608 MiB budget. All ten prepared native unary factors retain
mandatory fixed positive tasks with distinct starts, and the existing difference
system survives. This source is eligible; compiler availability and prepared
shape do not supply a rejection.

The first order commits and raises a start lower bound from 0 to 49. Both
input-order controls, with and without that pair, return native-checked witnesses
in 74 nodes with no conflicts or learned clauses. The overlay raises propagation
work from 333,115 to 358,556 and prepared factors from 413 to 416. The annotated
control preserves the source selectors and reaches the 256-decision cap with
557 conflicts; default VSIDS reaches it with 44. Both return unknown. These
selector contrasts establish no order-specific gain. Traces stop at 128 records;
the complete solve statistics and native witness checks remain recorded.

Stop the bounded first-pair proposal: it enforces the partition and propagates,
but supplies no useful search reduction or reusable conflict beyond the retained
controls. Together with the authored prepared shared-start barrier, this does
not qualify a dynamic consumer. This is a bounded applicability disposition,
not a claim about every scheduling source or pair policy. The source runs are
satisfaction projections, with no original-objective, independent MiniZinc
witness/proof or throughput claim. Explicit annotations remain authoritative;
the pair-first arm is only a separate diagnostic control.

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

With `repairSets`, optional `ownerReplay=true` reuses the captured source freedoms
for the same five-owner comparison as the finite trajectory diagnostic. This
overlap requires closed original integer declarations and closed working integer
bounds, at most 4,096 integer/real coordinates, and an exact checked center.
Original real backings join every free residual and consume no source integer
freedom. Missing bounds decline this finite overlap explicitly, retaining the
mixed cold reports; they establish no whole-family inapplicability. Mixed owners
use the original authority rows; finite-owner closures refer only to their
conditioned working model. Every accepted point passes `checkMpsWitness` on the
original columns. Real snapshots without exact values remain unavailable; finite
domains are never invented by this replay. CP owners consume the exact strict
working cutoff through `beginExact`, retaining rational equality and direction
without a floating representability restriction.
Optional `primalCompletion=true` gives CP owners the original-source point checker
and its rational objective in working units. An improving primal may accompany
`Unsupported`; it has no exact completion or cutoff-closure authority. Approximate
reals retain their declared source qualification and no certified-coordinate label.
`completionReplay=true` separately records each query's first unresolved leaf and
replays its unchanged pins through `LeafRealCompletion`. It saves reconstructed
source rows and point qualifications; diagnostic replay costs are outside the owner
allowance and reported separately. Owner preparation, search, source checks and
close costs retain separate records. Control centers still require exact snapshots.
Reports distinguish completed conditioned optimization from a closed initial
strict cutoff: an improving point can accompany the former, while only a
nonimproving working query establishes the latter. Both the working and checked
original-source objective predicates must hold before reporting an MPS gain.

[MPS owner replay 8580](https://lab.eignex.com/jobs/8580), at `02d362ad1f`,
qualifies thirteen matched conditions on original p0033 and rgn hashes. From
p0033's checked 3302 point, both CP owners improve five of ten scopes, LS four and
each mixed owner one; every reported gain also improves the checked original
objective. Cold CP uses 12,770 nodes; retained uses 9,642 and observes at least 31
previously unused retained nogoods becoming useful. The retained sequence reaches
its fragment allowance once; later completed fragments retain their proper stop
reason after reset. Allocation and observation overspend remain separate costs,
and node differences include retained RNG/activity as well as explanations.
Rgn's three exact non-binary cutoffs remain unavailable to CP; LS exhausts its
allowance and both mixed owners retain feasible nonimprovements. No domain is
invented and no original row is omitted from mixed completion.

[Maximization control 8585](https://lab.eignex.com/jobs/8585), at `cca43a8fe8`,
checks an exact affine real backing with source objective `0.1*y` and scale ten.
All five owners find the checked gain from zero to `1/10` when the source integer
is free; fixed-input queries admit no strict gain. These instrumented native
checks grant no independent witness certification or unpinned proof authority.

[Modglob owner replay 8628](https://lab.eignex.com/jobs/8628), at `725db3a1ce`,
captures three identical five-owner conditions without inventing finite domains.
The containing allowance is one million decision equivalents and each fragment
has 200,000 equivalents/two seconds, with unchanged native LP funding. No fragment
or containing allowance overspends. Both mixed owners find the same two checked
gains to 33.52M and close only the fixed-input cutoff. CP declines the exact
non-binary cutoff; LS returns no point at the fragment cancellation boundaries.
Cold mixed uses 53,933,567 funded LP work units and three preparations versus
31,535,079 and one retained preparation. Cumulative thread allocation is 552.7
versus 506.5 million bytes. These ordered instrumented observations establish
neither a repair-quality nor a throughput advantage.

The small-fragment [pilot 8614](https://lab.eignex.com/jobs/8614) gives retained
mixed repair one gain against cold nonimprovements, but materially overspends its
5,000-equivalent fragments: LS spends about 74,000 equivalents per attempt and
mixed overspends by up to 17,781. That pilot earns no equal-work admission credit;
its repair-quality difference is absent with the adequate quantum in 8628.

[Composed held-out capture 8658](https://lab.eignex.com/jobs/8658), at
`31228ae7b3`, binds eight checked strong-walk centers across ClockTriplet, RCPSP,
CarpetCutting and the assignment control, with seeds 7/19. All 640 semantic
selections complete capture, including packing's Boolean auxiliaries; 397 are
count-matched and 89 decline explicitly. Both conflict-acquisition modes contribute
300 further count-matched scopes, with 27 actual supported-guidance consumptions.
All 940 groups bind the same center, freedoms, complement pins and strict cutoff
across five owners; every control-file hash matches its owner ledger.

The inner LS repair uses its ordinary repair recipe, distinct from the outer
`cbls-extended/fixed` acquisition walk. LS finds no strict gain. Cold CP finds ten
and retained CP eight, all from conflict
scopes and accepted by the native original-source checker. Retained CP has two
better final points and two worse ones across the 851 supported matched queries;
the other 847 tie. Its two deeper ClockTriplet points reach 162 rather than 163,
while two missed gains retain 164. These four queries exhaust their fragments.
Cold CP closes 719
conditioned cutoffs and retained CP 745, with unfinished queries retained separately.
Their actual node totals are 135,517 and 216,069; cumulative thread allocation is
10.97 and 41.04 GB. Retained CP records no newly useful old-clause flag.
Fragment overspend is at most one decision equivalent; containing work stays within
200,000. Read-only explanation observations take 11.76 seconds across the CP ledgers,
excluded from decision work but included in wall time. No completed native exhaustion
is retagged by those observations. Mixed owners decline these global shapes.

Conflict acquisition, selection and the producer's repairs retain separate costs;
all 300 declared source-coordinate descents reach checked policy-local endpoints,
which supply no basin or global proof claim. Walk witness/counter parity holds for
all eight captures. These ordered diagnostics support neither unconditional retained
CP replacement nor mixed admission on these finite globals. Witness acceptance and
conditioned closures here are native evidence, without independent proof checking.

[Structured inner replay 8735](https://lab.eignex.com/jobs/8735), at `7a1f8a2baa` on
Mac/aarch64, reruns 8658's eight requests with `ownerRecipes=cbls-plateau/fixed,cbls-extended/fixed`.
All 940 groups reproduce 8658's centers, freedoms, cutoffs and selectors. Default LS
and the plateau owner find no gain; the extended walk recipe finds two on 18–19-input
ClockTriplet Random scopes where both CP owners exhaust their fragments. Cold and
retained CP keep their ten and eight conflict-scope gains, which no LS owner reaches.
Every LS owner spends 1,361,600 moves without overspend. LS, plateau and extended
take 7.7, 8.5 and 11.2 seconds and 4.6, 4.8 and 6.6 GB of thread allocation, against
23.0/15.4 for cold CP and 10.2/41.1 for retained CP. The extended source offers
9.7 million compounds and loses 6.0 million to pins; neither gain commits a compound.
All twenty post-repair declared descents stop at policy-local endpoints without
further gain.

Owner seeds 101–108 on ClockTriplet separate recipe from trajectory. With walk seed 7
([8756](https://lab.eignex.com/jobs/8756), `26b725bf17`, Linux/amd64), default LS,
plateau and extended LS find 12, 9 and 8 gains over 960 matched queries, scattered
across single seeds; cold and retained CP find 22 and 24, repeating on the same two
conflict scopes. The extended seed-7 gains do not recur, while one extended seed
reaches 163 on a scope where both CP owners always exhaust. With walk seed 19
([8757](https://lab.eignex.com/jobs/8757), Mac/aarch64), the three LS owners find
0, 1 and 2 gains against 41 and 28 for CP.

[Catalog arms 8764](https://lab.eignex.com/jobs/8764), at `239460eaa0` on Linux/amd64,
replays the same 940 groups with the implicitly seeded catalog `cbls-implicit/fixed`
and `cbls-extended/fixed` arms. Neither finds a gain. The scopes conflict with
implicit ownership: 625 of the 851 supported rows partially free a seed global, and
280 free exactly one of its coordinates, which ownership locks after a restart.
CarpetCutting also has 28 pinned definition outputs reading freed inputs. Pins are
never released to admit a compound. Reject structured inner LS as a stronger repair
for these captured freedoms: the catalog's definition sweep, structured scoring and
implicit ownership add cost without consistent gains over default LS, whose own gains
depend on the owner seed. This is not a general LS-versus-CP ranking.

Recipe `cbls-implicit-unused/fixed` adds the opt-in `unused-values` owner source to
the implicit catalog arm. The AllDifferent seed globals behind 8764's partial-owner
rows are closed permutations: ClockTriplet's 20 inputs over `1..20` and
assignment-cycle's three inputs over `{0,1,2}`. Every freed coordinate's domain holds
only occupied values, so those fragments are locked by the constraint itself;
RCPSP and CarpetCutting seed Cumulative or NoOverlap instead.
[8809](https://lab.eignex.com/jobs/8809), [8810](https://lab.eignex.com/jobs/8810),
[8811](https://lab.eignex.com/jobs/8811) and [8821](https://lab.eignex.com/jobs/8821)
replay identical scopes at `5a017677b6`. On the `assignment-unused` slack control,
across owner seeds 101–104, the swap-only owner finds none of 16 gains. The unused
owner finds all 16, like default LS and both CP owners. On closed assignment-exchange
and ClockTriplet, both owners' rows, tallies and endpoints are identical.
Assignment-cycle's walk was censored, so it supplied no center.
On AircraftLanding airland01, CP improves 7 of 40 captured scopes per mode. The
swap-only owner improves none, and the unused owner improves 6 by moving landing times
to unoccupied values; one row frees a single seed coordinate. CP reaches the same
point on three of them and a deeper one on the other three, and improves one scope the
unused owner misses. All six post-repair descents stop at checked policy-local
endpoints; both acquisition modes capture the same scopes. KMedian pmed01's scopes are all closed by CP. On pmed02 no owner gains, and
the unused owner's element-heavy scoring costs about ten times the time per move, so
its 10-second containing allowance ends after 12 of 80 selector rows. The conflict
selector's 1,024-coordinate limit stops 8811 at pmed02.

The fixed-work single-arm solve [8825](https://lab.eignex.com/jobs/8825), at
`9e5809eae7`, uses 100,000 equivalents, 20 seconds and seeds 1–3. All 450 checked
witnesses are valid. Against the swap-only implicit arm, the source wins 44 of 45
KMedian runs (mean −1536) while running time-bound at a tenth of the work. It ties
59 of 66 closed-permutation runs with two wins and five losses; CyclicBandwidth's
104-input scope exceeds the scan share and draws random offsets. Implicit
AircraftLanding stays infeasible in 38 of 39 runs, against all 39 without it. Unowned `cbls/fixed` stays as strong
on KMedian (22 wins, 23 losses) and dominates AircraftLanding and ClockTriplet. The
source therefore restores value-set mobility without qualifying a catalog change, and
remains opt-in.

[Merged-proposal MPS comparison 8697](https://lab.eignex.com/jobs/8697), at
`c6f1b10a5a`, consumes four actual source-role guided pairs from the checked modglob
35.1471M center. Each frees x201 with one of x251/x223/x221/x255; original real
backings x1/x3 and the full continuous residual stay free under every source row.
All four groups match centers, freedoms, complement pins and strict cutoffs across
five owners, with no overspend at the adequate 200,000-equivalent fragment quantum
and 1-million containing allowance. Native LP funding retains its existing limits.

Both CP owners decline the exact cutoff because the retained CP double contract
cannot represent it. LS exhausts all four fragments without a point. Cold and
retained mixed repair each find two checked gains, to 35.1131M and 33.5240M, and
close the other two conditioned cutoffs. Gain queries remain incomplete and do not
refute their initial cutoffs. Cold mixed repair spends 72,987,226 funded LP work
with four preparations and six reuses; retained repair spends 37,396,124 with one
preparation and nine reuses. Both perform ten checks, certify ten bounds and close
seven leaves. Modeled allocation is 204,023,371 versus 151,049,699 bytes, separate
from cumulative thread allocation of 787,605,896 versus 720,080,344 bytes or RSS.
These are ordered instrumented queries with native original-source checks, without
independent witness/proof certification or a throughput claim.

[Exact-cutoff replay 8731](https://lab.eignex.com/jobs/8731), at `2ce578d797`,
retains 8580's p0033/rgn and 8697's modglob centers, original source hashes and
byte-identical control ledgers: 17 conditions across five owners on Linux/amd64,
debug enabled. Execution receipts enforce one CPU and 4,608 MiB cgroup memory;
the profile command leaves heap and GC selection to the JVM. Both CP owners enter all
seven non-binary rational conditions. Cold CP closes two rgn cutoffs and retained
CP one; the remaining rgn conditions and every modglob CP query return
`Unsupported` after uncertified continuous completion, without closure authority.
P0033 retains five checked gains and five cutoff closures per CP owner, spending
12,770/9,642 cold/retained nodes. Both mixed owners retain two modglob gains.
All 48 returned points pass native original-source checks; independent witness and
proof certification remain absent.

Rgn cold/retained CP spends 25,164,472/19,137,664 standalone LP work units and
254.7/208.0 MB cumulative thread allocation; modglob spends
191,465,246/52,769,205 units and 502.0/261.2 MB. These include unsuccessful
completion. CP fragment overspend is at most one decision equivalent. The retained
small-fragment controls preserve LS/mixed overspends reaching 5,119/915 equivalents;
no containing allowance is exceeded. These instrumented costs support exact-cutoff
capability, without an equal-work speedup or owner-replacement qualification.

[Primal capture 8796](https://lab.eignex.com/jobs/8796), at `c7082ceb70` on
Linux/amd64, retains the first unresolved leaf of three rgn and eight modglob CP
queries, including its fixed discrete assignment and reconstructed residual source
rows. All eleven numerical proposals pass native original-source tolerance checks;
unchanged-pin `LeafRealCompletion` returns eleven checked witnesses. None meets
nominal exact feasibility. The repeated retained modglob leaf declines integer,
rational and exact-point checks; exact continuation declines its dimension.
Original-source acceptance supplies no missing completion certificate. Separate
diagnostic replay costs are 33–108 ms and 11.3–12.3 MB thread allocation on rgn,
241–369 ms and 50.4–72.7 MB on modglob. These are instrumented completion costs,
excluded from owner allowances, without independent certification or throughput credit.

[Continuation replay 8819](https://lab.eignex.com/jobs/8819), at `19cd81b0eb`,
reuses [8804](https://lab.eignex.com/jobs/8804)'s exact-only baseline and the same
byte-identical 8731 controls. Both captures use Mac/aarch64, identical runtime and
options, and advisory one-CPU/4,608-MiB budgets. Each CP owner returns three modglob
strict improvements: two change one original input and reach about 35.1131M and
33.5240M; the third is a same-input rounding improvement. Every modglob CP query
remains `Unsupported`, including the two queries without a witness. Rgn gains no
point and retains its two/one cold/retained native closures. P0033 keeps five gains
per CP owner at 12,770/9,642 nodes; both mixed owners keep two modglob gains.
All 54 returned points pass native original-source checks: 48 nominal exact points
and six tolerance-qualified CP primals, without independent certification.

Early-return 8804 gives retained CP four same-input improvements and no changed-input
gain; continuation keeps better siblings, with one earlier rounding point absent.
Both continued CP owners spend 24 modglob nodes and the baseline's
184,161,945/52,630,634 standalone LP work units. Search, reuse observation and source
validation allocate 467.5/265.5 MB of thread bytes, versus 432.8/236.5 MB in the
exact-only baseline. Common center preparation adds 2.8 MB; owner close costs are
recorded separately. Rgn CP allocation also grows, without a new witness. No
containing allowance is exceeded; inherited small-fragment LS/mixed overspends
reach 5,119 equivalents. These ordered instrumented costs qualify primal capability,
without a speedup, exact-closure or owner-promotion claim.

Keep the existing owner admission. Small finite controls, held-out checked centers,
ordinary finite execution and these original MPS overlaps support no uniform owner
replacement. Retained LP state reduces repeated preparation in this MPS overlap;
retained CP has both better and worse bounded endpoints, and ordinary source-input
proposal promotion fails qualification. Reject a whole-family winner rule based on
freedom or auxiliary counts. Unavailable owners, unfinished repairs and conditioned
closures retain their local scopes and supply no global source proof.

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

`jointWalkMillis=30000` runs the actual retained mixed driver from the checked
saved center under one 256-operation, 1-billion-work lifetime. It writes checked
incumbent trajectories and costs instead of independent cold conditions. Optional
`sourceInputPass=true` enables an internal nonadaptive proposal-order experiment:
a source-input seed pass of pair freedoms precedes wider requests after each
strictly improved cutoff. Adaptive ALNS retains its requested widths. Pending
conditions and pass position survive slices; the pass supplies no local or global
exhaustion claim. This option is a diagnostic consumer, not default admission.

[Source-role controls 8455](https://lab.eignex.com/jobs/8455), at `fba60b259a`,
repeat the twenty conditions with declared inputs eligible as seeds and complement
pins. Pair scopes contain two original inputs and broad scopes contain 32;
modglob's two checked improvements and all rgn outcomes match the baseline.
The extra freedoms alone establish no better basin or quality.

[Retained walks 8474](https://lab.eignex.com/jobs/8474), at `418e3e7fb3`, compare
the same checked centers with and without the source-input pass. Each has thirty
seconds, 256 LP operations, one billion LP work units and the existing 512 MiB
modeled allocation funding limit. On modglob, the unchanged schedule publishes one
checked improvement to 35.11M before timing out in a broad repair. The pass
publishes twelve exact original-source-feasible improvements to 24.72M, then ends
at its modeled allocation funding boundary: 119,301,837 work, 550,068,394 cumulative
modeled allocation bytes, 46 checks and fifteen candidates. Its instrumented wall
time is 7.15 seconds and cumulative thread allocation 2,966,698,768 bytes, versus
30 seconds and 27,475,404,008 thread bytes for the control. These ledgers are
distinct from retained memory or RSS. Rgn stays at 82.2 in both walks with identical
native work and selected-coordinate totals. Ordered diagnostic runs establish no
uniform throughput gain, independent witness certification or source optimality;
ordinary execution and stronger finite controls remain qualification gates.

[Ordinary qualification 8569](https://lab.eignex.com/jobs/8569) preserves baseline
`bdc5c225c9`, roles-only `3d5f0d3352` and input-pass `9023451bd9` arms with unchanged
funding, five sources, both exact policies, default/companion execution and seeds
1/2/3. Its 36 modglob cases reject production adoption. Ordinary
default-mode baseline objectives are 20.7846/20.7846/20.7729M; roles-only gives
21.4092/20.7846/20.7846M and the input pass 20.7846/21.2303/21.1986M. All nine
finite witnesses pass independent solchecker. Three of six companion pairs improve
from 35.1471 to 35.1131M; the other three tie, and default exact-mode points tie.
The 27 refined witnesses have native source acceptance but unsupported independent
checking of nonterminating rational values. The diagnostic gain from the saved
center neither reaches the stronger finite controls nor compensates these ordinary
losses. Source-role eligibility and the narrow pass require explicit diagnostic
construction; production callers retain their ordinary proposal admission.

The full cohort completes all 180 feasible outcomes: 153 witnesses pass independent
solchecker and the same 27 refined modglob witnesses remain unsupported. Across
60 matched pairs per treatment, the input pass has four wins, five losses and 51
ties; roles alone have one win, six losses and 53 ties, using an absolute objective
comparison tolerance of 1e-6. Losses include ordinary exact rgn and bell3a as well
as modglob. All 72 native p0033/gr4x6 optimum labels agree across arms; their proofs
are not independently certified. Original source hashes, Mac/aarch64 runtime,
options and non-solver dependency hashes match; each ref has one build fingerprint,
and compiler identity is unreported. This time-bounded cohort supports the
production-admission rejection without a uniform throughput or source-optimum claim.

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

### Scoped nonlinear failure classification

[8494](https://lab.eignex.com/jobs/8494) retains twelve raw queries and
[8495](https://lab.eignex.com/jobs/8495) nine unconditioned prepared queries at
`01d160c40e`, with source hashes frozen from the original 7906/7919 records,
Boolean scaling controls and three fixed-integer completions. Each observed/plain
pair has the same ten-second query allowance, P1, Serial GC, 3 GiB heap and default
representation limits. All 21 outcome pairs match: sixteen complete and five stop.
The recorded caller charge equals native work in every query. Capture is intrusive:
completed serialization costs range from 248 to 2,783,778 steps, and changed
work arbitration makes total native differences distinct from copying costs.
Timed work prefixes establish no speedup.

Prepared Economics first rejects `x11 = 0` against `-x11 < 0` in a partial family;
its full scoped record is complete. Raw sorting first rejects `x96 = 0` against
`x96 > 0`, with two repeated first representations among 180 rejections; its
candidate is complete but the overall source/context record is truncated. The
fixed-power completion rejects zero against `4*x1^2 - 8 = 0` and repeats once.
Prepared kissing rejects a prefix against a four-variable sphere equation; seven
first representations repeat, with incomplete support and repetition coverage.
Lasso constructs no rejected sample before root-reduction/raw or partial-sign/prepared
termination. These observations select bounded exact univariate predicate cells
for the refinement experiment: certified roots and strict endpoints can repair
free-coordinate samples, with complete CAD retained. They justify neither arbitrary
multivariate tangents nor lifting diagnostic text into checked exclusions.
Prepared source correspondence remains unavailable.

[8443](https://lab.eignex.com/jobs/8443) compares the sign/interleaving control
`1074186d94` with capture-disabled `cdf817e972` on all 68 frozen 7919 identities,
seed 3, P1, ten-second ordinary CLI budgets and source validation. All source hashes
and paired feasibility/proof/objective/exit outcomes match. Each arm retains 39 SAT,
26 native UNSAT, one UNKNOWN and two unchanged source-admission failures. Of the SAT
witnesses, 16 pass Dolmen 0.10 and 21 pass the Z3 5.1.0 algebraic fallback; one
power-syntax check is unknown and one DIMACS witness is unchecked. Native UNSAT
claims have no independent certificates. Hash-bound requests, original bytes,
traces, runtime/build identities and checker receipts stay outside Git in the Lab
records and the retained nonlinear-refinement campaign.

### Checked univariate predicate cells

[Raw 8621](https://lab.eignex.com/jobs/8621) compares twelve direct queries at
`38cff63082`, including fixed integer tuples and Boolean scaling controls. All
outcome pairs match: five SAT, five native UNSAT and two interrupted queries.
The fixed power query derives two cells in three coordinate attempts and reaches
its witness with 1,390 native units against 1,612. Its quadratic cell retains
`n = 2` as a query condition; its strict-positive cell has mandatory source support.
Raw Economics derives four source cells but adds 2,142 units without changing the
fallback trajectory. The large raw cases produce no checked cells. These direct
component queries do not establish ordinary CLI publication or integer-search gains.

[Prepared 8622](https://lab.eignex.com/jobs/8622) and
[holdouts 8623](https://lab.eignex.com/jobs/8623) compare the internal producer with
its disabled control at `38cff63082`, P1, ten-second query budgets, a 3 GiB child
heap and default representation limits. All nine prepared and seventeen holdout
outcome pairs match. Prepared Economics derives six checked source cells in
thirteen coordinate attempts, with five repaired coordinates. Its `x11 > 0`
support excludes the closed zero section and negative open sector. The producer
spends its 16,384-unit allowance, then retains the control's seven projected
polynomials and 38 lifted cells; total native work rises from 218,350 to 234,734.
Mbo and the three large interrupted prepared cases also exhaust this private
allowance without a candidate. A zero entry count does not establish that no
useful predicate exists.

The seventeen holdouts produce 33 checked cells: 31 with direct source support
and two conditional cells on the canonical degeneracy identities. Their retained
fixings remain explicit. Negated equality uses 617 native units against 3,735;
repeated-section uses 314 against 594. Positive-dimensional nullification reaches
a component witness with zero lifted cells against three, using exclusions
from independent unary predicates. Strict-root contradiction instead grows from
181 to 3,069 units. These observations establish bounded capability and mixed costs,
with the option disabled by default. Native UNSAT is separate from independent
source proof checking. Coordinate attempts, main rejected representations, complete
model counts and ordinary published witnesses are distinct observation surfaces.

Matched failure captures [8655](https://lab.eignex.com/jobs/8655) and
[8656](https://lab.eignex.com/jobs/8656) retain all 21 raw/prepared outcome pairs.
The fixed power query removes three main rejected representations, including one
repeat of the first rejection. Raw Economics retains one main rejection per arm;
prepared Economics retains 28, including one repeat. Raw sorting retains 180 with
two repeats per arm. Large prepared sorting/kissing captures are incomplete, so
their differing counts do not establish a reduction. Representation equality is
bounded diagnostic equality, not semantic algebraic equality; coordinate seed
attempts are counted separately. Observer copy work is intrusive and excluded from
the clean total-work comparisons.

The clean raw, prepared and holdout profiles run under Linux/amd64, Java 25.0.4.1,
one CPU and a 4,608 MiB cgroup limit. Their final `memory.current` observations are
1,684,279,296, 1,589,030,912 and 1,691,287,552 bytes, with no OOM or limit events.
These whole-profile readings are neither peak nor per-arm memory measurements.

[Ordinary source checking 8648](https://lab.eignex.com/jobs/8648) compares seventy
identities at `d05f174ec7` and the one-line activation build `cba0c4f8f4`, on
Mac/aarch64, P1/seed 3/10s with a 3 GiB heap. All paired source hashes, outcomes and
CLI exits match. Each arm retains 41 SAT, 26 native UNSAT, one UNKNOWN and two
unchanged source-admission failures. Of the SAT witnesses, 18 pass Dolmen 0.10 and
21 pass Z3 5.1.0; one power-syntax check is unknown and one DIMACS witness is
unchecked. Native UNSAT has no independent certificate. Across 59 completed native
pairs, recorded `nraWork` totals 5,752,275 in control and 5,903,557 with refinement:
five decreases and 54 increases. Ordinary mixed power rises from 48,240 to 49,992,
despite the direct fixed-query reduction. These costs retain the disabled default;
the activation build is separate from the published implementation.

### Coupled section refutations

Matched `coupled=both` profiles at `d80419eb83` keep identical hash-pinned sources and
settings, with the coupled option as the only per-arm difference and balanced charges
in every trace. A pair is completed when both arms reach the same terminal result; a
control that stops at its five-second deadline while the coupled arm finishes is
counted separately and supplies no work saving.

| Context | Pairs | Completed | Control stopped | Coupled conflicts / uses | Completed work, on / off |
| --- | ---: | ---: | ---: | --- | ---: |
| [Prefix controls 8745](https://lab.eignex.com/jobs/8745) | 16 | 15 | 1 | 16 / 31 | 644,109 / 57,410,906 |
| [Prefix controls, cells on 8746](https://lab.eignex.com/jobs/8746) | 16 | 14 | 2 | 16 / 31 | 780,380 / 39,305,754 |
| [Whole-family holdouts 8747](https://lab.eignex.com/jobs/8747) | 17 | 17 | 0 | 0 / 0 | 94,860 / 94,792 |
| [Coupled-support holdouts 8748](https://lab.eignex.com/jobs/8748) | 12 | 9 | 3 | 9 / 42 | 82,894 / 4,320,173 |
| [Raw originals 8749](https://lab.eignex.com/jobs/8749) | 9 | 7 | 0 | 0 / 0 | 848,620 / 848,599 |
| [Prepared contexts 8750](https://lab.eignex.com/jobs/8750) | 12 | 9 | 0 | 0 / 0 | 3,549,519 / 3,548,943 |

The degree-two, four-prefix UNSAT control learns one three-premise exclusion over
`x1² = 2`, `x2² = 3` and `x1*x2 + x1 + x2 = 1` before its first region; regions,
projected polynomials and lifted cells fall from 16/368/2,512 to zero, and native work
from 6,546,367 to 4,661 units. Each SAT prefix control excludes the coupled branch and
reaches its gate witness through one region instead of two. The checked-cell producer
and feedback leave these outcomes unchanged.

The twelve coupled holdouts were authored before any coupled result: three-coordinate,
quartic and degree-sixteen covers, strict and weak rational boundaries, repeated roots,
an exact singular algebraic zero, a rootless cover, a disjunctive cover that learns two
exclusions through retraction, a gated strict alternative and an uncovered coordinate.
Every coupled verdict matches the authored expectation. Quartic, degree-sixteen and
disjunctive controls stop at their deadline. Feasible supports add work: 569 and 829
units on the repeated-root and weak-boundary SAT cases, and 7,622 on the singular case,
whose zero falls back to exact algebraic evaluation. The uncovered case attempts no
support. Existing holdouts and raw originals contain no covered support; completed
pairs add 68 and 21 units. Prepared lowering replaces products by auxiliaries, so none
of the prepared contexts, including the three authored SAT controls, exposes a support.
Interrupted original pairs keep different deadline frontiers.

The prefix job ran on Mac/arm64 without hard limits; the others ran on Linux/amd64 with
one CPU and a 4,608 MiB cgroup-v2 limit, final `memory.current` 1.65–1.94 GB and no OOM
events. The coupled arm runs first in each ordered pair, so its elapsed time includes
cold compilation and supports no throughput claim; completed prefix queries allocate
238,711,816 against 21,210,918,248 bytes.

[Ordinary source qualification 8751](https://lab.eignex.com/jobs/8751) compares the 70
frozen 8675 identities at `d80419eb83` and the one-line activation build `185154fe70`,
P1/seed 3/10 s with source validation. All paired source hashes, outcomes and CLI exits
match. Each arm has 41 SAT, 25 native UNSAT, two UNKNOWN and two unchanged
source-admission failures; 39 SAT witnesses per arm are independently valid (18 Dolmen
0.10, 21 Z3 5.1.0), with the power-syntax checker gap and one unchecked DIMACS SAT.
Native UNSAT has no independent certificate. Across 58 completed native pairs, `nraWork`
rises by 8,820 units in 14 pairs and falls in none, the largest being 7,674 on the
irrational shared-embedding UNSAT control. No ordinary source exposes the targeted
support, so the option stays disabled by default; the activation build is not
published.

### Prepared supports and connected conjunctions

The five-arm matrix separates sign-only, single-predicate, preparation-only,
joint-only and composed checking. [Direct controls 8785](https://lab.eignex.com/jobs/8785)
at `1133442d74` cover twelve frozen cycle, shifted-root, repeated-root, strict,
weak, singular and SAT-alternative sources. All terminal outcomes match their
authored expectations. The inconsistent three-edge cycle consumes a six-premise
joint exclusion twice, including one reuse across its unrelated Boolean pair.
No individual predicate supplies that conflict. Regions, projected polynomials
and lifted cells fall from 2/52/324 to zero; native work is 3,817 against 77,034 in
the [literal merged single-predicate control 8818](https://lab.eignex.com/jobs/8818)
at `d9f127f275`. Sign-only work is 75,528. The same-head single control adds
37 bookkeeping units. Feasible conjunctions retain CAD and add work.

[Prepared branch comparison 8813](https://lab.eignex.com/jobs/8813), at `ee13e6cb6b`,
transports the frozen degree-two source's explicit `gate=false` condition through
its captured plan. [Direct comparison 8812](https://lab.eignex.com/jobs/8812) learns
the original three-premise conflict in 4,389 units. The prepared view joins three
opposite weak-row pairs into equalities and completes total graph substitutions.
Its consumed nine-premise explanation retains all six rows and three product
definitions, with 3,380 units of private support work inside the native totals:

| Prepared numeric branch | Sign-only | Single | Preparation | Joint | Composed |
| --- | ---: | ---: | ---: | ---: | ---: |
| Native work | 434,299 | 437,130 | 14,353 | 25,519 | 14,353 |
| Regions | 2 | 2 | 0 | 1 | 0 |
| Projected polynomials | 28 | 28 | 0 | 5 | 0 |
| Lifted cells | 179 | 179 | 0 | 2 | 0 |

Prepared parsing, source setup and condition transport add 769 separately reported
units in every arm. The mapped `gate=true` alternative stays SAT, with no learned
exclusion. [Graph capture 8816](https://lab.eignex.com/jobs/8816) explains the
unconditioned zero-support observation: its free branch takes `gate=true` and
assigns only one weak row from each conditional comparison. Those assignments
provide no equality covers. Capture retains the numeric condition, exact graph
and reconstruction fibres; its extra observation work supplies no cost comparison.
Prepared slot numbers provide no original-real correspondence.

[Whole-family holdouts 8801](https://lab.eignex.com/jobs/8801), at `814aa38899`,
retain all seventeen outcomes and expose no support. Composed/single/sign-only
work is 94,975/94,871/94,792 units. The twelve
[coupled and singular holdouts 8802](https://lab.eignex.com/jobs/8802) preserve the
existing individual conflicts and every authored outcome; three sign-only controls
reach their deadlines. Feasible and uncovered cases add 17–67 units over single
checking, including 64 on the singular algebraic zero. They supply no additional
prepared or joint conflict.

Direct, merged-baseline and mapped-branch profiles run on Mac/aarch64; the two
holdout jobs and graph capture run on Linux/amd64, Java 25.0.4.1. All native charge
sums balance. Ordered instrumented component timings and allocation records include
their actual cold starts and establish no whole-CLI throughput claim. Numeric
exclusions remain native scoped proofs, without independent certification.

[Ordinary qualification 8806](https://lab.eignex.com/jobs/8806) compares default
`814aa38899` with one-line composed activation `6020adaf8e`, P1/seed 3/10 s,
on the frozen seventy sources plus all twelve cycle controls. All 82 paired
source hashes, outcomes and process exits match. Each arm has 49 SAT, 29 native
UNSAT, two UNKNOWN and the two unchanged negative-literal admission failures.
Forty-seven SAT witnesses per arm independently validate: 22 with Dolmen 0.10
and 25 with Z3 5.1.0. The power-syntax witness stays checker-unknown and DIMACS SAT
unchecked; native UNSAT has no independent certificate. All 96 checker records
bind the original hashes and exact published witness bytes.

Across seventy completed arithmetic pairs, `nraWork` changes from 4,042,913 to
3,803,270 units, with four decreases and 22 increases. Every decrease belongs to
an authored inconsistent cycle. The existing 58 arithmetic pairs add 31,336 units
and improve none. Whole-CLI receipts retain preparation, search, native validation
and close costs; independent checker costs stay separate. Repeated-cycle UNSAT
changes from 207,971 to 31,869 native units, 2.32 to 1.58 seconds and 110,208 to
97,276 KiB peak RSS. Its SAT neighbour changes from 133,185 to 150,425 units,
2.06 to 2.26 seconds and 107,520 to 112,808 KiB. These single ordered physical
observations establish no throughput claim. All 164 receipts bind their unchanged
raw solve records. CLI execution uses Linux/amd64, Java 25.0.4.1, a one-CPU and
4,608 MiB cgroup limit, without OOM events; checker receipts retain their separate
Darwin execution. The coupled option stays disabled by default.

[Matched original witnesses 8820](https://lab.eignex.com/jobs/8820) compares default
`a5398c4699` with activation `3df7f4e7f6` on the unchanged three job-8750 SAT
sources. All six complete-source witnesses independently validate, four with Z3
and two with Dolmen. No whole-source gain follows from the conditional conflict:
native work rises 71,756→72,215, 796,188→802,455 and 79,470→79,942. Source hashes
and outcomes match. Setup failures in diagnostic 8797 and the additional prepared
family profile 8817 yield no solver observations; both were cancelled. The latter
adds no claim beyond the completed mapped-branch matrix and whole-family source
qualification. No local compilation, tests or numerical workloads ran.

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

### Nonlinear integer completion controls

[8425](https://lab.eignex.com/jobs/8425) runs all twelve `nonlinear-integer-v1`
sources at `1c297c2502`, ordinary defaults, P1, seed 3, ten seconds and debug
disabled on Linux/amd64. All original hashes match the fixture manifest. Six
sources select nonlinear-integer execution, five finite CP and one polynomial-real
execution. Nine SAT results include seven models accepted by Dolmen 0.10 and two
power-syntax cases the checker cannot admit. The two native UNSAT claims have no
independent proof certificate; `open-no-integer-root` returns UNKNOWN at fixed-work
exhaustion.

That UNKNOWN records 768 nodes, 517,381 native units, twelve projected polynomials
and twelve lifted cells. Its whole-CLI external-time receipt records 3.77 seconds
and peak RSS of 124,248 KiB; allocation was not collected. Its neighborhood counters
report 178 completions, four refuted and 174 undecided. These single-arm records
supply routing and cost controls. Aggregate counters do not identify per-cursor
failed completions, repeated proof support or newly excluded tuples. In particular,
`open-integer-product` records both backtracking and local-search segments despite
its final zero-node aggregate. Guarded exclusion reuse needs a checked source
explanation and a comparison with visited-tuple filtering under the same producer.

[8674](https://lab.eignex.com/jobs/8674) compares source-cell reuse with visited-tuple
filtering over those twelve sources and the seventeen frozen
`nonlinear-integer-cells-v1` controls, at seeds 3/7, P1 and ten seconds. Activation
builds `6997f69ea7` and `2eeca2840e` share the `c241d008a0` implementation and enable
the same checked producer; their only difference is the integer reuse default.
Production defaults remain disabled. Reported pairs match source hashes, host,
JVM options, runtime and dependency fingerprints. Recovery places pairs on both
Mac/aarch64 and Linux/amd64; absolute costs across those platforms are separate.

On Mac/aarch64, the four- and eight-coordinate square controls show these native
work reductions. Exclusions count unvisited, in-window tuples, each avoiding a fixed
completion; repeated support counts further hits on the same cursor-owned cell.

| Coordinates / seed | Excluded / repeated support | Completion checks, visited / reuse | Native work, visited / reuse |
|---|---|---|---|
| 4 / 3 | 1251 / 1201 | 2514 / 1263 | 1615550 / 1116842 |
| 4 / 7 | 1337 / 1286 | 2580 / 1243 | 1656474 / 1047984 |
| 8 / 3 | 1258 / 1218 | 2185 / 927 | 2057568 / 996087 |
| 8 / 7 | 1258 / 1216 | 2325 / 1067 | 2191273 / 1131326 |

Their whole-CLI wall times fall from 1.07/1.09/1.20/1.60 seconds to
0.93/0.89/0.90/0.96 seconds in table order. Peak RSS changes from
208272/235760/236896/234496 KiB to 211040/219792/211424/225120 KiB.
These external, uninstrumented receipts measure the entire CLI JVM; allocation
was not collected, and they do not isolate the eight-cell store's memory.

Costs are mixed. `open-no-integer-root` excludes 870/868 tuples and records 521/519
repeated supports, reducing completion checks from 1615/1609 to 745/741 while native
work rises from 615290/618372 to 623464/626984. The one-coordinate square repeats
that mathematical control. Two-coordinate squares and degree-four powers also
spend more work. The mixed singular-real holdout excludes 610/691 tuples, but work
rises from 2038864/2103521 to 2062041/2114584; its Linux seed-3 wall time rises from
5.90 to 6.48 seconds. These unresolved cases retain UNKNOWN.

The cubic SAT neighbor excludes 4/26 tuples with 3/23 repeated supports and retains
both Dolmen-accepted witnesses; native work rises at seed 3 and falls at seed 7.
The degree-seventeen control produces no cells or exclusions. Boolean, repeated-root,
rational-window and wide-integer neighbors retain their scope and boundary controls.
Avoided completions establish guarded reuse beyond visited tuples, while the mixed
costs and unchanged observed quality retain the disabled defaults.

[8732](https://lab.eignex.com/jobs/8732) compares nearest integer-cell complement
proposals with filtering alone and ordinary defaults over the same twelve original
and seventeen cell controls. The eight square/power core cases and nine whole-family
holdouts retain their frozen membership. All 174 cases use seeds 3/7, P1, ten seconds
and debug disabled on Mac/aarch64. All 58 source/seed triples match original manifest
hashes, host, runtime, non-solver dependencies and the 3 GiB Serial-GC options.
Activation builds `b24e966420` and `c3bd7f671b` share the `52d0038215` implementation
and producer; only the steering default differs. The third arm keeps ordinary
defaults. Production producer, filtering and steering defaults remain disabled.

Every triple retains its reported SAT/UNSAT/UNKNOWN outcome. Of 84 reported models,
72 pass Dolmen 0.10 or Z3 5.1.0 source checking; twelve checks on the power-chain and
conditional-product sources remain UNKNOWN with `E:unbound-id`. Native refutations
have no independent proof certificate. Cancelled [8725](https://lab.eignex.com/jobs/8725)
retains a setup error: finite routes reject nonlinear-only CLI flags. The activation
builds preserve ordinary routing; infrastructure retries reuse the full selection.

The policy jointly projects ordinary proposals into the caller window and the
retained-cell complement. Allowed tuples count distinct in-window tuples reaching
fixed completion within each private cursor. On the held-out cubic SAT neighbor,
each seed reports one changed proposal and earlier checked witness access with
fewer completion queries. Repeated
support hits before that witness fall from 3/24 to zero. These are useful proposal
transitions with unchanged candidate caps and full original-source checks.

| Cubic seed | Allowed tuples, filter / steer | Completion checks, filter / steer | Native work, filter / steer | CLI wall seconds, filter / steer | Peak RSS KiB, filter / steer |
|---|---|---|---|---|---|
| 3 | 2 / 2 | 11 / 8 | 69094 / 31062 | 0.58 / 0.57 | 141664 / 126160 |
| 7 | 14 / 1 | 34 / 4 | 602170 / 29581 | 1.19 / 0.56 | 206208 / 126048 |

Ordinary cubic defaults spend 33393/550748 native units, 0.46/1.13 CLI seconds and
125904/214304 KiB. The seed-3 default remains faster in physical wall time despite
the steering arm's lower native work. External-time receipts measure the entire
uninstrumented CLI JVM; allocation was not collected.

The square controls expose the cost of leaving a known forbidden hull: more allowed
tuples can mean more failed completions. Both square-8 seeds retain UNKNOWN.

| Square-8 seed | Allowed tuples, filter / steer | Completion checks, filter / steer | Native work, filter / steer | CLI wall seconds, filter / steer | Peak RSS KiB, filter / steer |
|---|---|---|---|---|---|
| 3 | 118 / 916 | 927 / 1725 | 996087 / 1793974 | 0.86 / 1.27 | 235520 / 221696 |
| 7 | 160 / 959 | 1067 / 1866 | 1131326 / 1934238 | 1.17 / 1.45 | 230864 / 214016 |

Square-8 ordinary defaults spend 1674203/1777577 native units. Square-2, square-4
and degree-four powers also spend more work under steering. On
`open-no-integer-root`, allowed tuples and completion checks stay at 566/745 and
562/741 while work rises from 623464/626984 to 644806/648322. Changed proposals alone
establish no improvement on that source.

The mixed singular-real holdout remains UNKNOWN. Allowed tuples rise from 599/560
to 726/746, checks from 1590/1607 to 1717/1793 and work from 2062041/2114584 to
2169998/2255906. Peak RSS changes from 233424/219728 to 227120/229824 KiB; CLI wall
time changes from 1.44/2.05 to 1.67/1.80 seconds. The degree-seventeen control has no
cells or changed proposals; its ten-second stops and varying completion prefixes
supply no steering gain. Repeated-root and wide-integer neighbors retain checked
witnesses without entering selection; Boolean, rational-window and endpoint
controls retain their scope outcomes. Fast cursor controls separately cover caller
conditions, exact signed/wide endpoints, cancellation, slicing, charging and disposal.

The checked cubic gain supports the bounded optional mechanism. Additional failed
completions and mixed costs retain the disabled defaults and provide no general
throughput or memory improvement claim. Proposal exhaustion supplies no source proof.

[8805](https://lab.eignex.com/jobs/8805) compares integer-root admission with ordinary
defaults, filtering and steering over the same 29 frozen sources, seeds 3/7, P1, ten
seconds, source validation and CLI resources. Activation builds share the
`50066736c6` implementation and differ only in defaults. All 232 cases ran on one
Mac/aarch64 host with matching source hashes. Every triple keeps its SAT outcome, and
no arm loses an UNSAT result. Only the root arm turns ten rootless controls from
UNKNOWN into native UNSAT: the four squares, four powers, `mixed/singular-empty` and
`open-no-integer-root`. It spends no completion queries on them. The cubic witness
takes one completion and 3,033 native units, against 8/4 steered checks and
33,393/550,748 ordinary units.

That run exposed one cost: `wide-integer-square` exhausted the 65,536-unit equation
allowance in an algebraic ceiling search, declining `n*n = 10^40` at 66,107 units
against 498. Bracketing roots on the integer grid fixes it.
[8835](https://lab.eignex.com/jobs/8835) reruns ordinary defaults and the root arm at
`32d0b5f978` on the same host, 116 cases with unchanged outcomes.

| Source / seed | Native work, ordinary / roots | CLI wall seconds | Peak RSS KiB |
|---|---|---|---|
| `powers/square-8` / 3 | 1674203 / 316 | 1.01 / 0.21 | 202480 / 106848 |
| `degree/power-17` / 3 | 20174126 / 1065 | 10.10 / 0.21 | 216032 / 106784 |
| `mixed/singular-empty` / 3 | 7193251 / 318 | 4.00 / 0.23 | 234464 / 107344 |
| `cubic/root-neighbor` / 7 | 550748 / 1501 | 0.59 / 0.22 | 206736 / 112320 |
| `wide-integer-square` / 3 | 498 / 2999 | 0.22 / 0.23 | 111504 / 113488 |

Other refuted controls spend 309–1,332 units. Sources without an admissible equality
spend at most 16 more units on the scan, with unchanged trajectories and witnesses.
`overflow-cancellation` declines its zero difference. Guarded, rational-window,
annulus, repeated-root and finite-routed controls keep their outcomes. The twenty
native refutations have no independent certificate and no lab reference row; they
match the manifests' mathematical expectations. In both jobs each arm reports 28
models: 24 pass Dolmen 0.10 and four power-syntax checks remain UNKNOWN with
`E:unbound-id`. These self-authored fixtures are built around unary equalities, so
the disabled default stays until a held-out corpus qualifies it.

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
the observation contract.

[Coordinated barriers 8468](https://lab.eignex.com/jobs/8468), at `e110d0dff2`,
retains four hash-pinned sources, seed 7 and 128 counted moves with ordinary and
extended structured recipes. All eight observation-parity checks and 312 native
source checks pass. Every proper subset of each three- or four-input block fails
to improve at the recorded centers. Complete blocks improve two ordinary
assignment-three endpoints, two/three ordinary/extended resource endpoints,
one placement endpoint under either recipe, and two/one assignment-four
endpoints. Each comparison visits three endpoints. Extended assignment-three
already reaches zero at its first endpoint and admits no further block gain.
This retains the stronger existing remedy and selects resource, placement and
four-input assignment barriers for bounded selector comparisons. These small
capability controls establish no default-route admission or general speedup.

[Selector comparison 8497](https://lab.eignex.com/jobs/8497), at `e8d10688c6`,
uses those four explicit centers on Linux/amd64 under JFR, with seeds 7–22.
All 320 CP repairs complete and pass native original-source checks. Semantic
removal reaches objective zero in every trial. The effective freedoms contain
three source inputs, or four for the larger assignment control.

| Source | Semantic improvements | Adjacency improvements, matched trials |
| --- | ---: | ---: |
| Assignment three | 16/16 | 6/16 |
| Resource three | 16/16 | 6/16 |
| Placement three | 16/16 | 4/6 |
| Assignment four | 16/16 | 6/16 |

Random, WorstObjective and eligible count-matched samples find no improvements.
On placement, ten Random and ten Adjacency requests include a root-fixed role
and leave only two effective inputs; those unmatched trials are retained and
find no improvement. Every other request matches the semantic source count.
The assignment-three result is redundant with extended structured descent's
already observed zero endpoint; it supplies no additional remedy there.

Complete assignment/resource repairs spend one CP node; placement spends two
to five. Median repair thread allocation is 179,656–223,008 bytes for semantic
blocks versus 121,560–142,952 for Random. Semantic construction and closure
spend 12/24/50–53/15 modeled visits in table order. Control visits cover closure
only; physical selection measurements include their full existing acquisition.
Semantic selection allocates median 1,760–2,216 bytes and takes 37–74 microseconds.
Shared reflection/network/selector setup takes 11.4 ms on the first source and
0.10–0.14 ms thereafter. These ordered instrumented captures retain construction
and repair costs without establishing a speedup. The bounded opt-in mechanism
admits complete improving freedoms; default-route family admission remains in
[#3630](https://github.com/Eignex/klause/issues/3630).

### Scoped finite conflict ranking

[8465](https://lab.eignex.com/jobs/8465), at `2c304f8a10`, compares root CP
attributions on assignment, resource, placement and weighted Latin controls with
seeds 1/3/17 and effective freedom counts 2/4/8 where admitted. Its 135 repairs
match counts. Acquisition improves all 27 guided trials, reaching the controls'
known optima; those points cannot be credited to ranking. Placement ranking
improves 6/6 repairs against acquisition-ablated random's 1/6 and objective
removal's 6/6. Assignment/resource callbacks are unavailable. Latin ranking and
ablated random improve 0/9 against objective removal's 3/9. Its 45 linear-shaped
Latin descents are censored and receive no endpoint classification.

[8498](https://lab.eignex.com/jobs/8498), at `fe34f18ae9`, adds count-matched
random-fragment acquisition and explicit feasible greedy acceptance in descent.
All 135 root selections, attributions and acquisition/repair outcomes reproduce
8465. Across both origins, all 270 effective counts match; 56 acquired points,
99 repaired points and 270 descent points pass native original-source checks.
Every descent reaches a qualified coordinate-policy endpoint, without a basin
claim. Each trial shares 2,000 native work units and five seconds across
acquisition, selection, repair and descent; no trial exhausts either allowance.

Fragment placement consumes attributions in 2/6 trials and improves 3/6 repairs
against ablated random's 1/6, adjacency's 4/6 and objective removal's 6/6.
Acquisition finds no improving placement point. Guided/ablated native work totals
are 9/7 and thread allocations 6.00/5.88 MB. All three guided improvements survive
the declared descent. Fragment assignment/resource guidance remains unavailable.
Latin consumes guidance in 1/9 trials but repairs improve 0/9; its acquisition
finds objective 47, which the selected repair loses, while objective removal
improves 3/9. This qualifies bounded CP attribution consumption, not replacement
of existing selectors or independent acquisition. Defaults remain unchanged.
Both Linux/amd64 captures retain original hashes, identity mappings, settings,
cutoffs/pins and JAR/runtime identities. Ordered JFR-instrumented timings establish
no throughput claim; native checks establish no independent witness or proof claim.

[8540](https://lab.eignex.com/jobs/8540) and [8541](https://lab.eignex.com/jobs/8541),
at `8fedad8329`, add the actual semantic selectors, three-input targets and the
three-/four-input cycle controls. Both use identical JAR/runtime identities and
the same allowance, owner, centers and seeds. Across 936 attempts, 102 semantic
packet sizes mismatch their target and decline without repair or truncation.
All 834 admitted counts match, with 164 acquired, 229 repaired and 834 descent
points passing native original-source checks. Every admitted descent reaches a
qualified coordinate-policy endpoint; all improvements survive it. Source hashes
match all 48 requests. The 135 earlier root CP selections and outcomes reproduce.

Matched fragment-origin improvements are:

| Source | Movable counts | Trials | Conflict | Ablated random | Semantic | Adjacency | Objective |
|---|---|---:|---:|---:|---:|---:|---:|
| Assignment cycle | 3 | 3 | 0 | 0 | 3 | 1 | 0 |
| Resource cycle | 2/3 | 6 | 0 | 0 | 3 | 1 | 0 |
| Placement cycle | 3 | 3 | 1 | 0 | 3 | 3 | 0 |
| Assignment four-cycle | 4 | 3 | 0 | 0 | 3 | 1 | 0 |

The placement gain uses 9 native units and 3.01 MB thread allocation against
ablated random's 5 units/2.91 MB and semantic removal's 13 units/2.80 MB.
Its acquisition finds no improvement at this matched target. Assignment/resource
attributions remain unavailable. Root acquisition reaches every cycle's optimum;
its points remain separately credited. Complete semantic scopes equal or exceed
conflict repairs on the two-input controls. Neither signal nor row-sized semantic
freedoms improve Latin at the matched four-input target. Retain bounded conflict
ranking for opt-in comparisons; these results reject promoting it over the existing
selectors or acquisition companions. They do not decide numerical feedback or
establish broad finite-source performance.

### Scoped finite numerical ranking

[8644](https://lab.eignex.com/jobs/8644) and [8645](https://lab.eignex.com/jobs/8645),
at `9084692daf`, recover the cut-disabled/enabled comparisons after preparation
and upload failures. Four original linear sources use seeds 1/3/17, root or
random-fragment acquisition, and effective counts 2/3/4/8 where admitted. Each
trial shares five seconds and 2,000,000 containing units across acquisition,
selection, cold CP repair and declared feasible descent. Native search and
modeled source work remain separate cost components. Semantic packet shapes
are absent from this linear cohort; the preceding CP study retains those controls.

All 1,344 freedom counts match. The 444 acquired, 476 repaired and 1,344 descent
points pass native original-source checks. All repairs survive the qualified
descent, without cancellation or allowance overrun. All 48 original hashes match;
both Mac/aarch64 captures retain identical JAR/runtime identities, mappings,
settings, actual query pins and strict objective rows. These native checks and
ordered JFR timings establish neither independent proof nor production throughput.

At the two-/three-/four-input root targets, disagreement repairs all nine cycle
trials against numerical-acquisition-ablated random's zero. Both acquisitions
already supply objective zero, as does the cheaper CP prefix. Root ranking gains
therefore add no incumbent beyond the independent acquisition companion.

Fragment exchange at two inputs supplies no improving acquisition: refutation
ranking repairs 1/3 against ablated random's 0/3 and adjacency's 2/3. Every arm's
descent reaches zero there. Neither numerical ranking improves the matched
three-/four-cycle targets. At eight inputs on the four-cycle, refutation ranking
repairs 1/3 against ablated random's 0/3, with no acquisition gain; its zero
objective survives descent. Adjacency repairs 3/3. Median containing work is
36,750/36,570/2 and thread allocation 3.06/3.02/0.91 MB for
refutation/ablated/adjacency. Captured supports name only one to three of the four
required coordinated inputs; one conditioned refutation need not identify the
complete improving freedom set.

At two inputs on the root knapsack, disagreement repairs 3/3 against ablated
random's 2/3, but objective removal also repairs 3/3. Numerical acquisition supplies
no improving integer point, and every arm descends to objective two. The captured
fractional coordinate near one third is retained as its exact binary rational;
it receives no integer-witness credit. The declared-row relaxation optimum 4/3
does not make that numerical point an exact feasible certificate.

Each cut policy consumes 54 disagreement and 30 refutation rankings. The remaining
refutation trials report `NoRefutation`; 30 disagreement trials report agreement
with cuts disabled and no point with cuts enabled. All 672 paired selections and
acquisition/repair/descent objectives match across cut policies. Enabled captures
retain one installed, natively checked cut in each of 252 numerical trials;
disabled captures retain none. Maximum charged source work is 36,906/6,273 and
modeled allocation 97,584/69,364 bytes for disabled/enabled cuts. Those observed
costs do not equate native search units with source work or establish a general
cut-policy speedup.

[8652](https://lab.eignex.com/jobs/8652) and [8653](https://lab.eignex.com/jobs/8653),
at integrated code `0a0d85b82c`, reproduce all 1,344 selections, acquired/repaired
objectives, projected supports and descent outcomes. All counts, source checks,
allowances and 48 source hashes pass again. Both use identical JARs; the
Mac/aarch64 and Linux/amd64 runtime identities remain separate. This confirms
integration with the merged producer and preserves the measured decision,
without pooling repeated controls or comparing physical time across platforms.

Retain the bounded opt-in rankings for comparisons. The recovered controls reject
promoting either over existing selectors or independent acquisition: surviving
gains remain narrower than the stronger controls and require additional numerical
cost. They establish no broad finite-family advantage or basin classification.

#### Source-row seed completion

[8781](https://lab.eignex.com/jobs/8781), at `3e242c1a40`, repeats the cut-disabled
8644 requests with both completed arms and adds the held-out
[transport](smoke-corpus/finite-repair-evidence/README.md) family: 30 requests and
1,080 trials on Mac/aarch64, with one JAR/runtime identity and all 30 source hashes
matching. All 403 acquired, 388 repaired and 1,047 descent points pass native
original-source checks. No improvement is lost in descent, and no trial is
cancelled or overruns its allowance.

Fragment refutation completion consumes 24 packets and improves 23. No consumed
trial's acquisition improves, so the gains belong to the completed freedom. At each
control's exact packet size, the improvements are:

| Source | Inputs | Completed | Raw refutation | Adjacency | Ablated, random, objective |
|---|---:|---:|---:|---:|---:|
| Exchange | 2 | 3/3 | 1/3 | 2/3 | 0/3 |
| Three-cycle | 3 | 3/3 | 0/3 | 1/3 | 0/3 |
| Four-cycle | 4 | 3/3 | 0/3 | 1/3 | 0/3 |
| Transport, held out | 4 | 2/3 | 0/3 | 0/3 | 0/3 |

Supports naming one to three of the four-cycle inputs complete to the whole chain.
Every proper subset of the 21 improving chain and transport packets, each missing one
member, exhausts without improvement, so the full freedom was required at those
centers. The non-improving transport packet closes a zero-cost cycle. Transport
supports name all nine inputs; completion supplies the cycle structure, not a missing
coordinate. Its 27 unrepairable packets stop at a column row whose remaining
compensators were already planned in the opposite direction. Knapsack packets
improve, but so do their proper subsets: row slack needs no coordinated freedom.

Paired with raw refutation over 108 request counts, completion has 18 wins and no
losses. Its 15 declines are requests smaller than every packet, and raw ranking
improved none of them. Completion adds no LP operation. Median extra modeled source
work is 68, 105, 142 and 1,371 units on exchange, three-cycle, four-cycle and
transport. Both numerical arms spend a median containing total near 36,000 units,
against adjacency's one native unit.
Disagreement completion matches raw disagreement in every trial: root points already
disagree on whole chains, and 90 of 108 sets are identical. Root refutation is
unavailable because acquisition reaches each optimum, so completion adds no incumbent
beyond that companion. Semantic blocks do not apply to these linear shapes.

Retain completion as a bounded opt-in consumer of refutation supports. These
self-authored controls establish a consumed coordinated freedom, not a default,
throughput, basin or broad-family claim. Adjacency remains far cheaper where it
succeeds. [8780](https://lab.eignex.com/jobs/8780) stopped at the first numerical arm
on a stale reflective constructor lookup, and the 8742 submission named an
unresolvable abbreviated ref. Neither supplied a comparison.

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

#### Scoped refutation reuse

[Accepted-API egout capture 8476](https://lab.eignex.com/jobs/8476) uses
`c12bbb5677`, exact semantics, frontier traversal, source cuts, seed 3 and P1,
with a 32-operation numerical allowance. Its original MPS SHA-256 is
`351ed046eb56e07662b3c2dba35800f5d2cee3fac18c1bd7fbc8dea4a0dcd8db`,
enforced against the retained 8087 source. It retains 18 of 45 attempted
observations with six refutations under the 16 MiB modeled observation limit. Omitted
states remain unknown. This instrumented capture establishes no throughput comparison.

Egout queries 7 and 11 retain identical exact refutation derivations, including
four source premise IDs, rational weights and upper restrictions on integer
coordinates 2 and 3. Their restrictions on coordinates 0 and 1 differ. Original
scopes pass fresh native checks; reducing those scopes needs its own check.
The capture establishes recurrence, without a reduced-scope proof or an independent
exported-proof check. The consumer freshly checks each support reduction and import
before closing a compatible leaf.

`param.source-refutation-reuse=true` enables the experimental invocation-local
consumer for ordinary numerical traversals. The default keeps it disabled.
Stage observations distinguish candidates, scope reductions, checked imports,
avoided numerical queries, verification work, cumulative modeled allocation,
retained bytes and evictions. Modeled allocation is separate from physical RSS.
Reuse work, including its verification subset, is already included in source LP
conflict-certification work and cumulative funded work; these counters are not additive.

`param.source-refutation-propagation=true` additionally consumes checked integral
unit-prefix exclusions through named source-bound atoms; it enables the same reuse
index and remains disabled by default. Compare it with cache-only reuse on the same
revision and whole-family holdouts, retaining ordinary automatic controls.
`numericalRefutationUnitDeductions` counts first implications and
`numericalRefutationUnitReplays` counts restoration under active antecedents.
Record actual shared decisions, numerical queries, complete sibling coverage,
incumbents, total funded work/allocation and whole-CLI resources. Cache lookup and
verification counters are subsets of that work; atom naming and clause construction
also spend the containing allowance. Lower query counts in an incomplete run alone
establish no benefit.

[Qualification 8696](https://lab.eignex.com/jobs/8696), at `81220267c6`, completes
162 cases: the nine frozen original sources, reuse disabled/enabled and automatic
controls under default/exact semantics, three policy seeds, P1/10 s and external
whole-CLI resource recording. All original hashes match. Each source/seed keeps
all six arms on one host/build/runtime; 126 cases run on Linux/amd64 and 36 on
Mac/aarch64. Distribution jars match across platforms. The runtime options are
3 GiB, Serial GC and `ActiveProcessorCount=1`; command intervals overlap at most
one case per host. These are policy seeds with one repetition each.

All 54 paired frontier comparisons preserve feasibility, exact objective and
native/source proof outcome. All 70 feasible points pass the lab's independent
MPS solchecker; native proofs have no independent exported-proof check. Every
reused `p0033` run proves 3089, every `gr4x6` run proves 4047/20, and every
`flugplinf` run proves infeasibility. The Mac `rgn` seed retains its witness in
both policies; its Linux seeds remain unknown. Other numerical outcomes remain
unknown. Automatic controls also prove `enigma` optimal at zero.

The default-tolerance Linux strata below report medians over the indicated
number of policy seeds. A one-seed stratum is one observation. Closed/pending
leaf counts describe different partitions and establish no coverage ordering.

| Source (seeds) | LP operations, off → on | Checked imports | Closed/pending, off → on | Funded work, million units, off → on |
| --- | ---: | ---: | --- | ---: |
| bell3a (2) | 818 → 817.5 | 232 | 322/304 → 385/326.5 | 99.57 → 98.46 |
| bell5 (2) | 1062 → 978 | 175 | 180/724 → 199/759.5 | 100.12 → 99.41 |
| dcmulti (3) | 166 → 151 | 23 | 31/115 → 33/112 | 285.94 → 278.38 |
| egout (2) | 930.5 → 638 | 275 | 357/228 → 359.5/198.5 | 192.64 → 146.70 |
| enigma (3) | 1100 → 1091 | 156 | 506/248 → 577/215 | 155.99 → 151.68 |
| flugplinf (3) | 1104 → 1021 | 88 | 552/0 → 555/0 | 23.12 → 22.50 |
| gr4x6 (3) | 45 → 45 | 0 | 33/0 → 33/0 | 2.7862 → 2.7885 |
| p0033 (1) | 1896 → 1324 | 576 | 1026/0 → 1003/0 | 27.60 → 18.80 |
| rgn (2) | 408.5 → 403.5 | 16.5 | 80/284 → 80/283 | 102.23 → 101.62 |

The complete `p0033` counts repeat in both semantics and all three seeds.
Its pool spends 779,675 work units, including 382,802 verification units,
48,090,660 modeled allocation bytes, including 44,462,192 verification bytes,
and peaks at 707,548 retained bytes. Total funded allocation grows from
5,118,124 to 53,016,528 bytes despite lower total funded work. Linux CLI wall
times are 8.09 → 6.92 s and 8.48 → 7.04 s for default/exact; peak RSS is
160,112 → 151,980 KiB and 162,976 → 156,268 KiB. The two Mac seeds have median
times 2.28 → 1.75 s and 2.17 → 1.90 s, with RSS 261,472 → 248,656 KiB and
260,632 → 251,776 KiB. Timings stay within their platform strata.

Local allocation admission retires 22 of the 54 enabled caches. Maximum pool
allocation is 67,094,459 bytes, within 64 MiB; maximum source funded allocation
is 152,120,890 bytes. `enigma` retires at 66,934,014 pool bytes, continues
numerical queries through the deadline and remains unknown. Its peak retained
storage is 1,047,419 bytes; charged evictions include retirement cleanup.
`gr4x6` gains no hits and adds 2,305 work units and 261,406 modeled bytes.
`flugplinf` saves operations but Linux median wall time increases
4.81 → 5.02 s and 4.73 → 5.02 s. The time-limited Mac `bell3a`, `bell5` and
`egout` runs complete fewer LP operations with reuse and retain unknown outcomes.
These costs support keeping reuse opt-in and automatic routing unchanged.

[8657](https://lab.eignex.com/jobs/8657) retains the rejected 85524cc2b1
certificate-loss control; freshly checked imported refutations must also discharge
objective-bound coverage. [8671](https://lab.eignex.com/jobs/8671) retains the
127fc9dd2a control where `enigma` exhausts 512 MiB of cumulative allocation.
The invocation-local admission limit preserves ordinary numerical funding.

##### Unit-prefix exclusions

[8741](https://lab.eignex.com/jobs/8741), at `1101fa4693`, completes 162 matched
cache-only, prefix and automatic runs over the same nine source hashes, default/exact
semantics, three policy seeds and P1/10 s. Egout and p0033 supply the captured cases;
the other whole families are held out. All 27 source/seed groups retain one
host/build/runtime across their six arms: 78 cases on Mac/aarch64 and 84 on
Linux/amd64, with 3 GiB, Serial GC and `ActiveProcessorCount=1`. Command intervals
overlap at most one case per host. All 69 feasible points pass independent original
MPS solchecker checks; native proofs have no independent exported-proof check.
All 162 whole-CLI resource receipts are retained, including after worker replacement.

Every source consumes prefix deductions. Completed flugplinf refutations save
38 queries and 405 decisions, with higher allocation. Gr4x6 retains optimum
4047/20 but its five implications save neither queries nor decisions. The completed
native coverage costs below repeat across source policies; p0033 uses the separate
completion control described below. Allocation is cumulative modeled bytes.

| Source | Prefix implications | LP operations, cache → prefix | Decisions, cache → prefix | Funded work, million units | Allocation, decimal MB |
| --- | ---: | ---: | ---: | ---: | ---: |
| flugplinf | 307 | 1021 → 983 | 5745 → 5340 | 22.500 → 21.370 | 24.804 → 71.874 |
| gr4x6 | 5 | 45 → 45 | 156 → 156 | 2.788 → 2.792 | 0.528 → 0.959 |
| p0033 completion | 755 | 1324 → 1425 | 9495 → 6882 | 18.799 → 20.968 | 53.017 → 83.102 |

The ten-second p0033 comparison loses three cache-only optimum proofs; neither
policy loses feasibility, but one exact prefix run retains 3095 instead of 3089.
One default rgn prefix run loses the cache-only witness. All other paired
feasibility, objective and proof outcomes agree. Automatic controls prove p0033
optimal at 3089 in all six runs. These losses remain part of the qualification.

[8755](https://lab.eignex.com/jobs/8755) allows 30 seconds for one p0033 policy
seed on Mac/aarch64 at the same revision. Both cache and prefix policies complete
optimum 3089 under both source policies; all six witnesses pass solchecker.
The prefix retains complete checked sibling coverage with 3147 nodes instead of
2005. Default whole-CLI wall time is 1.95 → 2.87 s, CPU time 4.45 → 6.47 s and
peak RSS 260208 → 279440 KiB. These are single observations, separate from the
Linux ten-second cohort. Default flugplinf RSS also rises: 237136 → 252624 KiB
over two Mac seeds and 140656 → 154072 KiB for one Linux seed; Linux wall time
is 7.04 → 7.59 s. Whole-CLI costs include lookup, naming, replay and verification.

[8730](https://lab.eignex.com/jobs/8730) and
[8739](https://lab.eignex.com/jobs/8739) retain stopped prototypes where naming
after a spent source grant ended traversal early; those lower work counts are not
gains. The consumer pauses before naming the retained split. Unit-prefix exclusion
is a checked opt-in capability with a finished-proof work reduction on flugplinf,
captured-case regressions and memory costs; automatic routing and heuristic
ordering retain their defaults.

##### Named refutation resolution

`param.source-refutation-resolution=true` enables checked pairwise resolution over
already named integral source-bound predicates, together with unit propagation.
It remains disabled by default. `numericalRefutationReuseResolutionProofs` counts
retained proofs, `numericalRefutationReuseResolutionHits` checked region imports,
and `numericalRefutationResolvedDeductions` actual unit consumptions.
`numericalRefutationSupportReasons` counts shortened reasons, without establishing
a changed trajectory. `numericalRefutationReuseResolutionUnfixedHits` and
`numericalRefutationUnfixedDeductions` additionally require the removed pivot to
remain undecided in the checked exclusion scope: neither immediate input proof
matches individually. Observation work spends the same cumulative allowance.

[Pilot 8795](https://lab.eignex.com/jobs/8795), at `7fbb3e59d6` on Mac/aarch64,
returns seven independently checked original MPS witnesses across eight exact-policy
cases. P0033 consumes 22 resolved deductions and one region import, preserving
native optimum 3089 at higher work/time. Unit to resolution whole-CLI wall time is
2.85 to 3.32 s and peak RSS 287,696 to 300,880 KiB. Egout has no complementary
candidates before pool retirement; shortening 5,911 reasons leaves its checks and
pending leaves unchanged. A mistaken miplib3 flugplinf selector admits no case and
supplies no holdout evidence. The pilot precedes support/scalar/scratch metering
corrections; use the qualification revision for current cost comparisons.

[Qualification 8799](https://lab.eignex.com/jobs/8799), at `9fb998f3fd`, completes
72 Linux/amd64 cases on the same nine original hashes as 8741: captured egout/p0033
and seven whole-family holdouts, cache/unit/resolution/ordinary controls under both
semantic policies, seed 3 and P1/30 s. Each source keeps one host/build/runtime for
its eight arms, with 3 GiB, Serial GC and one active processor. All 72 whole-CLI
resource receipts are retained and all 28 returned witnesses pass independent
original MPS solchecker checks. Native proofs have no independent exported-proof
check. These are single trajectories, not timing repetitions.

All paired numerical policies preserve feasibility, exact objective and native
proof outcome. P0033 proves 3089, gr4x6 proves 4047/20 and flugplinf proves
infeasibility, with zero pending leaves. Other numerical cases remain unknown and
have no witness. Ordinary controls find witnesses on every feasible family and
also prove enigma optimal at zero. The complete-proof costs below repeat in both
semantic policies; each cell gives cache / unit / resolution. Allocation is
cumulative modeled source bytes in decimal MB.

| Source | Numerical checks | LP operations | Decisions | Funded work, million | Allocation, MB |
| --- | ---: | ---: | ---: | ---: | ---: |
| p0033 | 1343 / 1456 / 1998 | 1324 / 1425 / 1899 | 9495 / 6882 / 10095 | 18.799 / 20.968 / 28.450 | 53.017 / 83.102 / 76.180 |
| flugplinf | 1021 / 983 / 1165 | 1021 / 983 / 1166 | 5745 / 5340 / 5707 | 22.500 / 21.370 / 25.378 | 24.804 / 71.874 / 69.726 |
| gr4x6 | 45 / 45 / 45 | 45 / 45 / 45 | 156 / 156 / 156 | 2.7885 / 2.7924 / 2.7925 | 0.528 / 0.959 / 0.972 |

Per semantic policy, resolved imports/deductions are bell3a 10/1, bell5 2/0,
flugplinf 0/2 and p0033 1/22; the other five sources consume none. Fewer retained
nodes describe different coverage partitions, not a coverage ordering or gain.
Peak index storage is 1,048,538 bytes, pool allocation 67,108,826 bytes and source
allocation 97,399,189 bytes, within their existing cumulative limits.

[Unfixed-pivot replay 8808](https://lab.eignex.com/jobs/8808), at `df8972371e`
on Linux/amd64, completes all eight p0033 controls and independently checks all
eight witnesses. Ten of the 22 resolved deductions per semantic policy retain an
undecided pivot in their exclusion scope, beyond individual input matching/unit
checks. The resolved-region import itself has a decided pivot. Every policy
preserves native optimum 3089. Observation charges preserve 1,899 LP operations,
1,998 checks and 10,095 decisions, at 28,450,217 funded work and 76,201,275 modeled
bytes. Unit to resolution whole-CLI wall time is 14.75 to 15.92 s in default
semantics and 14.00 to 17.67 s in exact semantics; peak RSS is 168,556 to 168,080
and 159,976 to 165,456 KiB. Ordinary controls take 2.88/2.79 s. Whole-CLI costs
include frontend preparation, native checking and cleanup; independent solchecker
checks add 6–9 ms. No efficiency or default-admission claim follows.

Both qualification jobs retain server-managed infrastructure losses and repeated
uncommitted groups before successful receipts. Interrupted attempts supply no
solver outcome or throughput evidence; they are not imputed as completed coverage.
The retained capability is checked support-based learning at a cost. Automatic
routing and all refutation-consumer defaults stay unchanged.

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

`refutationReuse=true` enables the invocation-local checked cache in this capture.
`feedbackControls=true operations=N` compares baseline, full feedback and one
ablation of support ordering, point selection, reuse and aggregation on the same
hash-matched source. It writes each arm's bounds, incumbents, query journal and
frontier under its own directory. The lab places this job normally; allow 600
seconds for the six ordered captures. Their inspection allowances are separate
from solving, and their ordered JFR timings establish no throughput comparison.

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
Optional `activeRows=true` consults fully established active source rows before
Boolean completion. It is a feasibility-only opt-in under the same cumulative
source LP limits; partial checks, unchanged-state skips, pauses and checked
conflicts have separate `numericalPartial` counters. Partial points give neither
incumbents nor objective bounds. Complete-check counts remain separate.
Incomplete active-row captures retain unknown applicability.
`activeControls=true` instead runs seven self-authored native exact-affine controls
with delayed and active-row consultations. One warmup and three measured rounds
alternate arm order, preserving the exact source JSON/hash, objective units, full
owner lifecycle time, decisions, complete checks and separate work counters. These
controls establish native scope behavior and cost; they are neither ordinary MPS
performance measurements nor independent proof checks.
Warmups also retain the shared numerical journal in `*-states.json`. Delayed
open states receive separately funded feasibility consultations in
`*-active-consultations.jsonl`, with at most two operations, 20M work and 64 MiB
modeled allocation per state under a shared ten-second inspection deadline.
These counterfactual queries publish no point or bound and do not guide traversal.
Measured rounds disable observation. Stops and omitted observations remain unknown.
For an original MPS request, `activeRows=compare` applies the same paired lifecycle
comparison to one shared prepared source context. Original bytes/hash, effective
semantics, objective scale and tolerance coverage accompany the native results.
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

`MpsProofFrontier frontier=true selection=true` retains up to 64 completed same-parent query
records in `selection.json`, within one million modeled entries, 16 MiB modeled
retention and a separate 30-second observation/inspection allowance. Records keep
exact points before primal recovery, original and Boolean-substituted source
systems, generating inactive-row masks, installed candidate status and signed
exact violations (`activity - rhs`). Positive violation identifies a cut excluding
the point; a satisfied installed cut remains a candidate. Records also retain
checked bounds/refutations, parent scopes, available sibling bounds, incumbent
values and visited-row eligibility limits. Missing values are unknown.
`retainedRecords` and `inspectionStopped` distinguish journal retention from
inspection that stops before every retained record is emitted or checked.
`operations=N` optionally caps the diagnostic traversal at 1–256 source LP
operations so observation-on/off controls can stop before the wall deadline.

Optional `replay=true` separately replays at most four retained point states with
empty and all-candidate masks, twice per mask, flushing `selection-replays.jsonl`.
Each fixed-mask replay has one source operation, 20M cumulative work and 64 MiB modeled allocation under a
shared ten-second diagnostic deadline. Replay creates fresh owners; its work and
native checks are separate from traversal accounting and independent witness
validation. The capture's existing original bytes, hash, native-system identity,
objective scale, revision, settings and platform bind both files. Neither diagnostic
establishes whole-CLI timing or a source verdict.
Replay prepares a fresh basis; a retained warm-start child can therefore have a
different point or certified bound. Repeated fresh replays test determinism for
one exact query and mask. The original captured point remains the scoring state.

`aggregate-probe=true` attaches the same journal and writes its parent records to
`aggregate-parents.json` through the maintained serializer. `aggregate-pairs.jsonl`
examines pairs of active source rows that can cancel all continuous activity,
including a continuous-only cancelling row, before checked integer rounding.
It retains exact weights, owner indices, positive violations and fresh native
checks separately from the input source-row residuals. This bounded family excludes
mixed residual rounding and aggregates of three or more rows.
`aggregate-mixed=true` adds every shared opposite-sign continuous pivot and applies
checked mixed rounding to the remaining residuals. Its bound index keeps the first
active singleton source side, preferring a negative residual; each cited side
retains its activation premises. This mode also includes continuous bound rows as
aggregation sources. It does not enumerate other bound choices or wider aggregates.
Inspection has 20M cumulative work, 64 MiB modeled allocation, 256-bit retained
scalars, 128-coordinate combined support, 16 KiB proof references and at most 64
positive candidates per parent. Native proof verification shares 100,000 steps per
parent. Parent serialization and pair inspection each have a separate 20-second
diagnostic deadline and issue no LP queries. Complete pair scans, unavailable
points, stopped proof checks and retention limits retain distinct observations;
zero candidates in an incomplete report establish no applicability result.

`aggregate-replay=true` retains the first parent with fresh source-checked
aggregate proposals and writes `aggregate-replays.jsonl`. Four fresh-basis arms
disable separation, enable individual cuts, and append the accepted aggregates to
the individual family before native admission. The fourth arm prepares bounded
opposite-bound projections directly from the same source, including discovery
in its query costs. Each arm has one LP operation, 20M work and 64 MiB modeled
allocation; all four share a 30-second deadline.
Discovery costs remain in the pair report. The replay reports installed and
checked candidate IDs, exact bounds, native checks and per-arm costs. These
diagnostic replays establish neither warm-basis parity nor whole-CLI throughput.
`aggregateCuts=true` enables cached source-bound projections in the measured
frontier traversal itself. Ordinary CLI experiments use
`param=source-aggregate-cuts=true`; the default is false and `source-cuts=false`
disables both individual and aggregate separation.

[Mixed-pair probe 8519](https://lab.eignex.com/jobs/8519), at `6f92f17c4b`, uses
the same original `rgn` source hash and traversal settings as 8478. Root query 0
completes 20,301 pairs and 820 pivot combinations, producing five positively
violated mixed cuts; all five pass fresh native checks. The largest exact
violation is `11748520715972943/36028797018963968`. Each combines a singleton
continuous bound and a mixed source row, then cites three residual bound sides.
None of the 40 individual cuts has positive violation at that captured point.
Root discovery and proof checks spend 1,042,668 modeled work and 35,367,372 modeled
allocation bytes. Query 1 reaches the shared allocation limit, leaving its proof
checks and all later states unknown. These are separation observations; checked
bound improvement requires an inserted-cut comparison.

[Root insertion replay 8577](https://lab.eignex.com/jobs/8577), at `179b91e487`,
uses the same original `rgn` hash and Linux/amd64 settings. All three fresh-basis
arms finish within one operation, with fresh native bound checks. The individual
bound is `921805910255297432406579/18889465931478580854784` (about 48.8).
Appending the five checked proposals raises it to
`2070285411688286118435853/37778931862957161709568` (about 54.8).
Individual query work/allocation are 956,585/421,164 bytes; the aggregate arm uses
1,073,195/441,568, excluding the separately reported discovery cost. Disabled
separation uses 420,964/262,364 and certifies about 48.8. All 45 aggregate-arm
candidates pass native admission. This motivates a bounded cached source-bound
projection family; it does not establish whole-frontier throughput.

[Cached-family replay 8590](https://lab.eignex.com/jobs/8590), at `8e8f150523`,
repeats those three arms and adds template-owned opposite-bound projection.
It retains 64 aggregate proposals and admits all 104 individual/aggregate cuts.
The freshly checked bound is
`641788481763964810311511928787631/11711468953074583855880403419136`
(about 54.8). Its 1,760,975 work and 2,454,257 modeled allocation bytes include
projection preparation and LP certification. This is a useful retained subset;
the proposal cap leaves the rest of the source-projection family unexamined.
It does not establish a universal candidate ceiling or whole-frontier benefit.

[Frontier integration 8610](https://lab.eignex.com/jobs/8610), at `e2622b92d8`,
enables the cached family in the retained traversal on the same original `rgn`
source, default tolerance, seed 3, Linux/amd64, P1 and 32 LP operations. The root
installs 40 individual cuts and 64 aggregates and reproduces 8590's checked bound.
All 104 cuts and the bound pass fresh native checks in each of the six retained
query records. The traversal prepares once and reuses the owner 31 times, reaching
a checked root bound of about 63 with 30 checked bounds, 61 retained nodes and
28 pending alternatives. It publishes no incumbent or terminal source verdict.
Total funded work is 14,081,025 and modeled allocation is 2,524,401 bytes, including
73,674 cut-preparation work and 546,560 cut-verification work. Floating work is
10,684,978; bound and conflict certification spend 2,853,848 and 164,378 respectively.
The 16 MiB capture journal retains six of 61 attempted records, leaving later
point inspection unavailable. This verifies traversal consumption and costs;
whole-CLI comparisons and held-out qualification are separate measurements.

[Ordinary qualification 8620](https://lab.eignex.com/jobs/8620), at `6feefc72f3`,
finishes all 128 cases: disabled, individual, aggregate and automatic-default arms,
exact and default tolerance, seeds 1 and 3, two repeats, four original MPS sources.
All 87 emitted witnesses pass the same independent checker identified in 8481.
The 64 proven outcomes are on `gr4x6` (`4047/20`) and `p0033` (`3089`); witness
checking remains separate from native proof checking. Retries preserve the first
16 accepted records and complete the unfinished cases under the frozen settings.

Each of the 32 comparison blocks shares its full build fingerprint. All CLI
JAR and benchmark harness hashes match across the cohort; the platform launcher and
runtime distinguish Linux/amd64 from macOS/aarch64. Every case requests P1,
3 GiB SerialGC, debug enabled and a ten-second solver budget. CPU seconds cover
the whole CLI process; physical RSS is distinct from modeled allocation. The
external resource receipt binds each raw solve record by SHA-256. Ranges below
describe the eight runs in each row and span both platform strata. Pairwise outcome
comparisons use matched runtime blocks. Whole-CLI wall times range from 0.46 to
10.40 seconds; these timings supply no pooled throughput estimate.

| Source | Arm | Witnesses / proven | Funded work, millions | Modeled allocation, MB | CPU seconds | Peak RSS, MiB |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| rgn | disabled | 4 / 0 | 80.043–160.067 | 2.767–100.198 | 10.21–20.93 | 176.6–373.0 |
| rgn | individual | 1 / 0 | 104.048–251.043 | 2.063–27.668 | 10.22–20.72 | 166.9–326.0 |
| rgn | aggregate | 2 / 0 | 120.076–549.544 | 3.478–69.697 | 10.21–21.04 | 165.0–338.3 |
| rgn | automatic default | 8 / 0 | 3.116–41.579 | 0.440–0.990 | 10.17–22.20 | 184.2–326.9 |
| gr4x6 | disabled | 8 / 8 | 2.781 | 0.266 | 1.39–1.82 | 101.7–195.5 |
| gr4x6 | individual | 8 / 8 | 2.786 | 0.266 | 1.13–1.99 | 101.7–197.3 |
| gr4x6 | aggregate | 8 / 8 | 2.813 | 1.209 | 1.17–2.13 | 103.7–195.7 |
| gr4x6 | automatic default | 8 / 8 | 2.649–2.781 | 0.243–0.249 | 1.83–6.85 | 148.1–211.2 |
| p0033 | disabled | 8 / 8 | 24.600 | 5.207 | 4.61–8.53 | 159.2–293.8 |
| p0033 | individual | 8 / 8 | 27.601 | 5.118 | 4.54–8.82 | 155.1–299.0 |
| p0033 | aggregate | 8 / 8 | 27.602 | 5.177 | 5.18–9.47 | 159.1–296.9 |
| p0033 | automatic default | 8 / 8 | — | — | 0.89–4.10 | 123.0–172.0 |
| pp08aCUTS | disabled | 0 / 0 | 908.824–1000.112 | 2.763–3.098 | 17.92–21.14 | 306.5–336.9 |
| pp08aCUTS | individual | 0 / 0 | 833.463–1001.080 | 2.785–3.252 | 19.24–21.09 | 284.6–320.9 |
| pp08aCUTS | aggregate | 0 / 0 | 804.047–1000.005 | 3.773–4.353 | 19.56–21.21 | 289.4–309.3 |
| pp08aCUTS | automatic default | 8 / 0 | 75.480–220.167 | 0.586–0.966 | 20.99–22.64 | 307.7–329.7 |

Funded totals include discovery, failed attempts, query preparation, numerical
work and certification. The automatic `p0033` route has no numerical-frontier
funding ledger. No automatic-default arm enables aggregation.

On `rgn`, the final checked nominal bound is about 67.4–70.8 with aggregation,
versus 72.2–75.0 for individual and disabled separation. Pending alternatives
range over 190–579, 288–601 and 396–632 respectively. Aggregation gains one witness
over individual separation and loses two relative to disabled separation in the
matched blocks. The stronger same-parent root bound therefore supplies no general
search or throughput improvement claim.

No held-out source accepts an aggregate from this bounded family. `gr4x6` visits
48 combinations: 24 have integral right-hand sides and 24 lack residual bounds.
`p0033` has no eligible projection attempts. Its four individual cuts remain
available. Aggregate preparation adds 26,336 work and 942,434 modeled bytes on
`gr4x6`, and 901 work and 59,222 modeled bytes on `p0033`, over individual cuts.
`pp08aCUTS` visits 231 combinations, including 112 support-limited attempts whose
applicability remains unknown. Zero retained cuts there establish no broader
inapplicability result.

The checked producer and explicit frontier option remain available for controlled
experiments. The 64-proposal traversal policy stays disabled by default: its root
inference is useful, while this cohort shows higher costs, weaker final bounds and
lost outcomes relative to disabled separation. Wider aggregates and other bound
choices remain outside this experiment.

[Infrastructure control 8481](https://lab.eignex.com/jobs/8481) pairs
`133546fd6f` and `2161236173` with aggregation disabled, across `gr4x6`, `p0033`,
`rgn` and `pp08aCUTS`, seeds 1 and 3, default and exact policies, and individual,
disabled and default traversal arms. All 96 cases finish on Mac/aarch64 with the
same original source hashes per problem, 10-second budgets, P1, 3 GiB SerialGC and
debug enabled. All 48 base/candidate pairs retain verdict coverage. Fixed-frontier
arms reproduce exact objectives and modeled work; timed default searches vary in
incumbents and work and support no throughput claim. Both revisions prove
`gr4x6` and `p0033`. The 80 emitted witnesses pass MIPLIB solchecker
`1.0.4+eignex-lab-longnum-1`, executable SHA-256
`2292d8dd2e14d43dd7c4962069dc278e216a7143eff4e1ce3c42278043c4c0c8`.
The 16 frontier `pp08aCUTS` cases emit no witness. Independent feasibility checks
do not validate the native optimality certificates. This control qualifies the
inactive infrastructure, before aggregate policy is enabled.

[Pure-pair probe 8478](https://lab.eignex.com/jobs/8478), at `f7a11d71cd`, retains
the original `rgn` hash, default tolerance, seed 3 and a 32-operation traversal on
Linux/amd64. All nine retained parents complete their 20,301 pair visits and 40
explicit combinations: 20 combinations eliminate integer activity and 20 leave
continuous residuals. No positive pure-cancellation cut is found. The diagnostic
spends 597,879 modeled work and 13,657,142 modeled allocation bytes, with zero LP
queries. Exact input-row residuals are recorded separately from cut efficacy, and
the retention-limited journal leaves later states unknown. This result selects
mixed-residual inspection rather than a general aggregation rejection.

[Observation-on 8431](https://lab.eignex.com/jobs/8431) and
[observation-off 8432](https://lab.eignex.com/jobs/8432), at `06e1a59e3b`, use the
same original `rgn` hash as 8265, one Linux/amd64 host, matching installed bytes,
runtime and options, seed 3 and default tolerance. Both stop at 32 LP operations
with identical stage counters: 63 checks and retained nodes, 5,482,611 floating
work, 230,751 preparation work, 1,841,250 bound-certificate work and 54,861
conflict-certificate work. Physical observation overhead is not a throughput claim.
The journal retains nine states before its conservative modeled byte allowance;
360 candidate checks, nine local bounds and four available sibling bounds pass
fresh native verification. Later missing observations remain unknown.

[Exact p0033 capture 8433](https://lab.eignex.com/jobs/8433) uses that revision and
host with its 8265 original hash. All 57 states fit: ten checked refutations,
28 completed points and 19 unavailable points. All 228 candidate checks pass.
For each of the first four parents, all-cuts replay gives a stronger checked bound
than empty-mask replay. Root work is 28,679 versus 32,019, respectively, within
one operation each. In the four `rgn` replay parents, no candidate has positive
violation at the uncut point and empty/all masks give identical checked bounds.
These states support point-based selection experiments while preserving useful
rounding controls. Native derivation checks are separate from independent witness
or exported-proof validation; the diagnostic establishes no source verdict.

[Repeated replay 8445](https://lab.eignex.com/jobs/8445), at `7f5c1aec09`, reproduces
the exact point, scope, bound and modeled work in both fresh repetitions of all
eight `rgn` parent/mask pairs. All 16 bounds and 640 candidate derivations pass
fresh native checks; no replay reaches its allowance. The original source hash
and Linux/amd64 platform match 8431.


`--param source-point-cuts=true` enables point-based selection only in the retained
numerical frontier; `--param source-cuts=false` disables separation. The ordinary
all-cuts policy remains the default. `MpsProofFrontier pointCuts=true` selects this
consumer, and replay includes a `selected` arm alongside `all` and `disabled`.
Each selected replay funds both its base query and at most one separation query
from the same 20M work/64 MiB allocation envelope, with two source operations;
the other arms keep one operation. Both generating masks and the strongest checked
completed bound are retained. A fresh selected arm starts with an empty pool.
Replay uses the first four distinct source scopes with captured points.

Selection retains at most 64 source cut IDs per compatible template and considers
at most 8,192 support entries, 65,536 scoring work and 1 MiB modeled allocation per
consultation. Projection/scoring work and allocation debit the containing ledger;
LP preparation, exact proof checks and numerical work retain their existing costs.
`numericalCutSelection*` observations distinguish visited/selected rows, unavailable
scores/points, incomplete scans, pool limits, separation rounds, work and modeled
allocation. A satisfied installed cut remains available, with its original exact
source derivation; no score admits or validates a cut. Pending separation retains
its completed base bound, and a declined round keeps the base split. Checked base
incumbents and terminals bypass extra separation. These are bounded policy controls,
not default-admission or throughput claims.

The selection work counter is part of preparation work, and selection allocation
is part of funded allocation; these counters must not be added to their totals.

[Point-selection rgn 8547](https://lab.eignex.com/jobs/8547) and
[p0033 8549](https://lab.eignex.com/jobs/8549), at `21c8b56c12` on Linux/amd64,
compare fresh disabled/all/selected queries twice under identical source scopes.
All 48 replay bounds and 1,088 candidate checks pass fresh native verification;
every repeated scope/arm pair reproduces its exact point, bound and modeled costs,
and none reaches its allowance. The p0033 sample contains three distinct scopes
because its base and selected root queries both occupy journal records.

At the p0033 root, selected IDs 0/1/2 preserve the four-cut bound while disabled
separation is weaker. The two-query selected round costs 52,375 work units and
82,320 modeled bytes, versus 28,679 and 39,084 with all cuts and 27,284 and 33,332
with separation disabled. On rgn, none of the forty cuts excludes the replayed
points. Selection omits all forty physical rows and uses one operation. Root work
is 450,500 versus 956,585 with all cuts, but selection allocation is 359,900 bytes
versus 262,364 when disabled. The all-cut exact bound is slightly stronger than
the identical selected/disabled bound; excluding satisfied rows does not establish
exact certificate parity. These are same-parent work and capability results, not
ordinary solve speedups or grounds for default promotion.

[Rebased p0033 8570](https://lab.eignex.com/jobs/8570), at `f37b058b74`, reproduces
all 24 replay operation counts, masks, exact bounds, work and modeled allocation
after integration with active-row capture. All available replay bounds recheck.

[Integration p0033 8639](https://lab.eignex.com/jobs/8639), at `94d416a07f`,
checks composition with the funded numerical-feedback journal on Linux/amd64.
Its four distinct source scopes produce 24 replay bounds and 128 cut derivations
that pass fresh native checks. Both repetitions reproduce points, masks, exact
bounds and modeled costs without reaching an allowance. Selected separation
preserves every all-cut bound with two operations and higher work and allocation
than the one-operation all-cut queries. The 32-operation capture retains 27
certified bounds, nine closed leaves and 19 pending leaves, spending 458,587 funded
work and 333,968 modeled bytes. These diagnostic results do not replace ordinary
held-out qualification.

[Ordinary comparison 8583](https://lab.eignex.com/jobs/8583), at `3a13972fcf`,
completes 128 ten-second P1 cases: eight arms over eight frozen sources, seeds 1/3
and default/exact policies. The development sources are rgn, p0033 and pp08aCUTS;
bell3a, egout, gr4x6, modglob and enigma are held out. Library JAR hashes match
throughout. Each source/seed block shares a host, launcher and runtime; blocks span
Linux/amd64 and macOS/ARM. Supported retries preserve all accepted records.
All 128 whole-CLI resource receipts are available. Of 56 reported witnesses, 54
pass source validation; two exact modglob controls remain unsupported because
the decimal-only checker cannot represent their rational values. The 36 native
optimality claims have no independent proof check.

All p0033 arms retain source-checked objective 3089 and native optimality. Both
seeds and policies reproduce these numerical ledger costs and coverage counts:

| Separation | Funded work | Modeled bytes | Checks | Closed leaves |
|---|---:|---:|---:|---:|
| Selected | 29,965,732 | 14,597,628 | 2,149 | 1,115 |
| All cuts | 27,600,734 | 5,118,124 | 1,995 | 1,026 |
| Disabled | 24,599,726 | 5,207,348 | 2,081 | 1,062 |

Rgn selection installs no cuts and closes 101–103 leaves versus 79–82 with all
cuts and 129 with separation disabled. It uses 65–68% of all-cut funded work
with 20.4–21.0 times its modeled allocation. On seed 1/default, whole-CLI peak RSS
is 172,996 versus 175,940 KiB and wall time is 10.31 s for both selected/all arms.
Pp08aCUTS selection adds 9.9–10.1 times the all-cut modeled allocation without
a witness or proof gain. No held-out source retains any candidate cuts. Gr4x6
retains objective 202.35 and native optimality in every arm; automatic defaults
also retain incumbents on the other sources and prove enigma's objective zero,
where the fixed-frontier arms remain unknown. Wall-cutoff frontier differences
without changed masks establish no selection efficacy. The mechanism remains
opt-in; these observations support neither default promotion nor a throughput claim.

#### Source numerical decision feedback

`MpsProofFrontier supportBranching=true` enables an internal decision consumer of the
same-parent journal. Installed checked cuts close to their LP-point boundary
rank fractional integer coordinates by support size and fractional distance.
The p0033 capture 8433 retains three nearly tight rounding rows at its root;
these exact-valued coordinates are pre-recovery guidance with numerical residuals.
This motivates a comparison of checked coverage and cumulative work against
source order, without reactivating gain history, probing or refutation priority.
The runtime adapter exposes actual query scopes, cut masks, points, source rows,
checked bound/refutation records and unavailable states to repair consumers.
Cutoff attribution requires the actual strict objective premise.
Collection and scoring debit the containing source funding. Qualification must
separate consumed choices, checked outcomes, total work, modeled allocation and
whole-process memory. Ordinary execution exposes no support-ordering option.
The frozen `codex/source-feedback-qualification` revision retains the experimental
CLI control used by the ordinary comparisons; its existence does not admit the policy.

[Pilot 8586](https://lab.eignex.com/jobs/8586), at `3fb3878bb6`, completes 48
ten-second P1 cases on four frozen MPS sources, seeds 1/3 and default/exact
policies. Installed distribution, runtime/options and build fingerprint match on
Linux/amd64; workers differ. All 23 reported witnesses pass the independent
source checker. Native proofs have no independent proof check.

On p0033 the consumer changes 26 choices and retains checked objective 3089.
Checks grow from 1,995 to 2,433, retained nodes from 2,051 to 2,579, funded work
from 27,600,734 to 32,951,173 and modeled allocation from 5,118,124 to 14,885,779
bytes. Source order completes all four native proofs; the consumer completes
three, with one final proof lost at the containing deadline despite zero pending
leaves. More conflicts accompany more search and establish no efficiency gain.
Rgn, bell3a and egout consume no changed choices and produce no numerical
incumbents. Their finite controls retain stronger source-checked outcomes.
Bounded retention leaves later support states unknown. These observations oppose
policy promotion; changed wall-cutoff frontiers establish no throughput benefit.
On seed 1's shared worker, whole-CLI peak RSS changes from 159,332 to 168,504
KiB under default policy and 161,792 to 165,592 KiB under exact policy. External
wall times change from 8.36 to 10.00 s and 8.49 to 9.80 s, respectively.
Seed 3's RSS changes from 155,156 to 172,628 KiB under default policy and
160,780 to 165,432 KiB under exact policy; wall times change from 8.55 to 10.39 s
and 8.67 to 10.09 s. These pairs are not independent timing repetitions.
All 23 artifacts missing from the original HTTP 502 fetch, including four resource
receipts, are recovered at their recorded byte sizes; the missing-file manifest is retained.

[Fixed-operation capture 8589](https://lab.eignex.com/jobs/8589), at `dd4ac66bbc`,
retains 32 states before the 8 MiB journal limit, with 26 changed choices.
All 128 cut derivations, 29 available bounds and three refutations pass fresh
native checks in their recorded scopes. Inspection completes; later records remain
unavailable. Its separate inspection allowance supplies neither an independent
witness/proof check nor a whole-process timing claim.

[Held-out comparison 8625](https://lab.eignex.com/jobs/8625), at `e43ed4c2ea`,
completes all 96 ten-second P1 cases on pp08aCUTS, gr4x6, modglob and enigma,
seeds 1/3 and default/exact policies. Arms compare numerical support/source order,
ordinary execution, equal-budget independent companions, and shared companions
with each ordering. The recovered retry preserves all 84 accepted records and
runs only the remaining 12 cases. All 32 support arms consume zero changed
choices; bounded unavailable records do not establish general inapplicability.
On gr4x6 all arms retain the native optimum and independently valid witnesses.
The support consumer preserves 45 LP operations and 65 nodes while funded work
grows from 2,786,191 to 2,919,225 and modeled allocation from 266,250 to 8,929,427
bytes. Shared-companion outcome changes with zero changed choices supply no
support-ordering credit. Ordinary execution closes all four enigma native proofs;
none of its numerical or companion controls closes one.

The 187 published points retain native exact/tolerance source qualifications.
Of 70 final witnesses, 56 pass independent solchecker checks. Fourteen modglob
points remain unsupported because their exact rational objective has no finite
decimal representation; native source checks remain separate. Native proofs have
no independent proof check. Its 28 native optimality labels separate 16
`attained-optimum` from 12 `tolerance-optimum` results. The Klause, CLI and Koblas JARs match across
Mac/aarch64 and Linux/amd64; each source/seed block retains one worker/runtime.
Whole-process RSS and external timing receipts remain paired within those blocks,
without independent timing repetitions or cross-platform throughput comparisons.
The seed-1 default gr4x6 pair changes peak RSS from 105,636 to 112,212 KiB and
external wall time from 1.91 to 1.81 s; this timing pair establishes no speedup.
The consumed pilot loss and zero-change held-out costs reject support-ordering
promotion. The scoped producer and internal comparison remain available. Final
composition uses separate held-out families because these sources overlap the
selection, reuse and aggregation qualification cohorts.

[Composed qualification 8705](https://lab.eignex.com/jobs/8705), at `1b8a73079a`,
completes 352 ten-second P1 cases with seeds 1/3 and default/exact policies.
Development sources are rgn, p0033, bell3a and egout. Held-out blend2, pk1,
stein27 and vpm2 are absent from this candidate's and its current prerequisite
and repair cohorts; membership and all settings are frozen before their outcomes.
Eleven modes compare ordinary execution, numerical baseline, the full chain,
each of four single-mechanism ablations, and independent/shared companions with
each ordering. The full chain enables accepted experimental point selection,
capped checked reuse and aggregation alongside the qualification-only support
control. Their production defaults remain disabled. Partial-row controls with
zero Boolean alternatives provide no additional promotion credit.

All 44 p0033 cases retain native optimum 3089. Full feedback changes 25 choices
and consumes 774 checked reuse hits. Removing support ordering consumes 619 hits
and less work; more hits accompany more search. These bare numerical counts
repeat across both seeds and policies:

| Arm | LP operations | Retained nodes | Funded work | Modeled bytes |
| --- | ---: | ---: | ---: | ---: |
| Baseline | 1,896 | 2,051 | 27,600,734 | 5,118,124 |
| Full | 1,753 | 2,735 | 25,699,806 | 86,681,461 |
| Without support ordering | 1,445 | 2,165 | 21,105,645 | 65,060,818 |
| Without point selection | 1,642 | 2,559 | 23,141,178 | 72,258,109 |
| Without reuse | 2,723 | 3,081 | 39,638,235 | 28,804,681 |
| Without aggregation | 1,753 | 2,735 | 25,699,196 | 86,622,239 |

The numerical-owner ledger includes feedback collection, scoring, retention and
its cut/reuse checks. Logical
open work for baseline/full/without-support is 12,073/15,333/11,949, in its separate
currency. On seed 1/default, their whole-CLI peak RSS is
280,384/262,528/274,896 KiB and external wall time 1.93/2.20/1.88 s.
Modeled allocation and physical memory give different comparisons.

Both companion recipes preserve p0033's optimum with 25 changed choices, while
numerical-owner funded work grows from 24,117,840 to 33,508,198 with support ordering.
Other owners retain separate costs. In seed 1/default's independent pair, native
CP propagation work grows from 1,877,830 to 5,726,364 and joint-repair funded work
from 443,880 to 591,324; these are separate components, not additive equivalents.
Whole-CLI wall time changes from 3.95 to 5.89 s and peak RSS from 318,576 to
361,264 KiB. Shared-companion costs and imported incumbent credit remain separate
from ordering credit. These policy seeds are not independent timing repetitions.

Rgn full feedback changes one choice, produces 64 aggregate candidates and selects
five cuts. Its checked point matches the numerical baseline. It spends
325,453,703 work units and 536,879,072 modeled bytes at the containing allocation
boundary. The seed-1 default arm without support ordering retains that point with
214,977,864 work units and 377,602,510 modeled bytes. Bell3a and egout change no
choices. Their companion witness differences supply no ordering efficacy evidence.

All 96 support-enabled held-out cases consume zero changed choices across
397–4,271 consultations each. Later journal states remain unavailable; this does
not establish general support inapplicability. Blend2 and vpm2 yield no numerical
or companion point, while ordinary execution publishes four source-qualified
points per family. Pk1's bare numerical arms yield no point; its companion
objectives vary with zero ordering changes and earn no ordering credit. Every
stein27 arm retains objective 18, but only ordinary execution closes its four
native proofs. Preserve these lost outcomes alongside bounded numerical gains.

[Checked trajectory capture 8707](https://lab.eignex.com/jobs/8707), at `b4b6d1d51b`,
compares the six bare arms on the declared p0033 development source with exact
semantics, seed 3, P1 and 256 LP operations. Every arm remains nonterminal and
produces no incumbent. Their native checked bounds start near 2530.9152; the
baseline ends at 2926.85, full feedback near 2840.7816 and without-support at
2940.35. Full feedback consumes 25 changed choices and 37 checked reuse hits.
Different leaf partitions and cooperative bound-sampling counts establish no
coverage ordering. All 1,380 serialized cut, bound, sibling-bound and refutation
instances pass fresh native checks in their recorded scopes, without independent
proof certification. Full feedback retains 32 of 405 attempted journal records
under its 8 MiB ceiling; external baseline/without-support observations retain
64 under 16 MiB. Inspection keeps those actual limits and unavailable states.
These ordered instrumented captures establish no throughput or whole-CLI cost claim.

All 845 published point records retain native original-source qualifications:
823 exact and 22 tolerance-qualified. [Checker run 8726](https://lab.eignex.com/jobs/8726)
checks the final witnesses without rerunning the solver: 190 are independently
valid, two exact-policy vpm2 witnesses remain unsupported because coordinate
`4/15` has no finite decimal representation, and none is invalid. The remaining
160 cases publish no final witness. All 48 native optimality labels are
`attained-optimum`; none has an independent proof check. All 352 cases use identical
distribution/runtime identities on Mac/aarch64, and each source/seed block stays
on one worker. Original hashes match, every whole-CLI resource receipt binds its
raw solve record, and checker augmentation preserves every solver record.
Timing and RSS remain descriptive observations without isolated timing repetitions.

The composed evidence rejects promotion of this bounded support-ordering consumer.
The scoped producer, internal consumer and reusable comparison remain available;
ordinary execution exposes no support-ordering switch. Accepted experimental
selection, reuse and aggregation retain their measured dispositions and disabled
defaults. [Finite rankings](#scoped-finite-numerical-ranking) retain only their
bounded gains with acquisition separately credited; their checked descents supply
no basin classification. The [repair-owner qualification](https://github.com/Eignex/klause/pull/3673)
retains existing admission: retained LP preparation savings and limited finite
repair gains support no unconditional owner replacement. No conditioned refutation,
relaxation point or unavailable record gains parent proof authority.

`feedbackDelivery=latest|prefix` selects the journal channel the support consumer
reads: the latest completed query, or the archive alone. `batch64=<base64 JSON array
of argument arrays>` runs up to 64 frontier requests, each in a fresh one-processor
JVM under `OUT/<index>`; a snapshot past its inspection allowance keeps the solve record.
Stages add `numericalFeedbackLateCollections` and `numericalSupportLateSelections`
for queries consumed after the archive stops.
`feedbackRequest=requested|full` selects whether queries delivered after the archive
stops hold only the support consumer's requested projection (the default) or the full
record. Stages add `numericalFeedbackCutEvaluations` and
`numericalFeedbackArithmeticTerms` for exact cut-violation visits, and the record adds
the solve thread's elapsed time and allocated bytes. `solveSeconds=<n>` replaces the
ten-second deadline, which includes preparation, with one that starts at the solve, so an
`operations=<n>` cap rather than the wall clock ends the query stream.

Requested projections use 8763's 20 hash-matched sources with fixed query streams
(`operations=192 solveSeconds=90`) before the ordinary ten-second frontier. Arms are
compared only within one job. [8814](https://lab.eignex.com/jobs/8814) (`3ac95ae9dc`,
Linux/amd64) gives identical splits, closed leaves, checks and support selections under
full and requested delivery wherever funding lasts. Exact cut arithmetic falls by 8–26% on
lseu, p0033, pp08aCUTS and rgn, while the other sources evaluate no cut. Funded
allocation falls only 1–4%, and full and requested latest delivery both exhaust 512 MiB
funding on fixnet6, khb05250 and p0282, full also on modglob. The no-feedback control
[8826](https://lab.eignex.com/jobs/8826) funds 0.3–2.5 MB against 25–540 MB with
feedback, so delivery construction, not cut arithmetic, carries 8763's cost.
[8829](https://lab.eignex.com/jobs/8829) charges requested records only for what they
allocate and saves just 3–8%. Once the archive filled its byte allowance, every later
query still started as an archive copy, overflowed and was delivered whole.

With the archive closed at its first overflow, [8833](https://lab.eignex.com/jobs/8833)
(`5d0a368f97`, Mac/aarch64) has no funding exhaustion: every source except misc07
completes its stream, and full and requested delivery make identical decisions.
Requested funded allocation is 1.1–9.4 times the prefix arm (9–11 MB), full 1.3–21
times, and requested is 43–87% of full. Solve-thread allocation stays within 5% between
the two latest arms. In the ordinary [8834](https://lab.eignex.com/jobs/8834) run on the
same revision and host, requested allocation is 41–64% of full, and gt2 exhausts funding
only under full. Flugpl reaches SearchExhausted at 1201500 in every arm, and pp08aCUTS
exhausts work in every arm. P0033 still loses the source-order incumbent 3089 under both
feedback arms. No witness or proof is independently checked, support ordering remains
an internal comparison, and a callback without a request keeps the full record.

[Delivery comparison 8737](https://lab.eignex.com/jobs/8737), at `2a3f3b564c` on
Mac/aarch64, runs source order, prefix and latest delivery on 21 exact ten-second
frontier sources (p0033 development, the rest held out). Latest delivery reaches
later states: p0033 consumes 379 late records with 353 changed choices, lseu 357 with
348. With every delivered record charged as a full copy, its modeled allocation reaches
0.18 to 0.54 GB against about 10 MB for prefix delivery. It exhausts the 512 MiB
source funding on six matched sources where the other arms do not, and on three
unmatched ones; pp08aCUTS exhausts work in every arm. Checks fall, and p0033 loses
the native incumbent 3095 that both other arms keep. Post-solve snapshot limits
discard nine records and gr4x6 times out in every arm.

[Delivery comparison 8763](https://lab.eignex.com/jobs/8763), at `c28758a1e1` on
Linux/amd64, repeats the comparison on the remaining 20 sources after delivery-only
records charge references and owned values. All 60 records complete and no arm
exhausts funding. Latest-delivery allocation remains 2.5 to 25 times prefix (25 to
272 MB against 9 to 11 MB), mostly cut-violation arithmetic per delivered query.
Only p0033 (149 late changed choices) and lseu (141) change decisions. Neither gains a
checked outcome: source order alone finds p0033's native incumbent 3095, and lseu closes
no leaves against prefix's 11. The other sources consume late records without changed
choices; their check counts differ by up to about a fifth in both directions under
the wall cutoff. No arm publishes an
independently checked witness or proof. Later availability establishes no
support-ordering benefit; the consumer stays an internal comparison.

#### Active rows before Boolean completion

[8424](https://lab.eignex.com/jobs/8424) and
[8437](https://lab.eignex.com/jobs/8437) compare seven self-authored native exact
contexts at `568f18115b` and `ce420b9e2e`. One warmup and three measured rounds
alternate arm order on Linux/amd64, P1/seed 3, with unchanged source LP allowances.
All 112 terminal claims recheck natively. Job receipts and installed-byte hashes
bind the gitless distributions; these are neither independent proof checks nor
ordinary CLI throughput measurements.

Closed, strict and independently pinned integer contradictions use zero Boolean
alternatives and one partial conflict, versus fourteen alternatives and eight
complete queries with delayed checking. The initially established guard avoids
six alternatives and four complete queries. Unresolved guard and Boolean-term
controls retain the checked optimum of 3 while alternatives fall fourteen to
eight and complete queries eight to four. Inactive guards preserve all required
complete checks. Partial points establish no witness or objective bound.

Keeping completed no-conflict keys across numerical-owner release reduces partial
queries from two to one on the inactive control, three to two on the unresolved
guard and four to three on the Boolean-term control, preserving every native
outcome. This retains neither points nor derivations. Costs still oppose blanket
activation: inactive-guard funded work grows from 4,219 to 6,525 units and modeled
allocation from 4,352 to 11,741 bytes. Boolean-term work grows from 8,659 to 8,755
and allocation from 9,728 to 18,937 bytes despite avoided decisions. These ledger
units are not machine instructions or physical memory. The capability remains an
internal opt-in; automatic early checking is disabled.

[Merged-capture qualification 8480](https://lab.eignex.com/jobs/8480), at
`a2e618f54b` on the merged numerical journal, preserves all 56 native terminal
checks and the decisions/complete-query outcomes above. Direct consultation of
41 retained delayed states produces 30 freshly checked refutations, with no points,
objective bounds, retention limits or inspection stops. Actual active-query
records retain their admitted zero-objective numeric subset and original scope.
Measured traversal rounds disable the observer. The inactive control still costs
6,525 funded units versus 4,219 and 11,741 modeled bytes versus 4,352; useful early
conflicts do not justify indiscriminate consultation.

Original-source [rgn 8482](https://lab.eignex.com/jobs/8482) and
[p0033 8483](https://lab.eignex.com/jobs/8483) complete sixteen ten-second paired
runs on shared prepared contexts with default and exact source policies,
respectively. No partial query executes: MPS lowering declares integer and real
coordinates, including binary columns and indicators. Both p0033 arms retain
native incumbent 3095; neither rgn arm finds one. Available native bounds recheck.
Different wall-cutoff frontiers give unmatched check/work counts, so these runs
establish no numerical throughput or fixed-work parity claim. The gr4x6 acquisition
in [8484](https://lab.eignex.com/jobs/8484) stops before solving because its worker
lacks `unzip`; it supplies no mechanism or applicability result.

Fixed 32-operation controls [rgn 8500](https://lab.eignex.com/jobs/8500),
[p0033 8501](https://lab.eignex.com/jobs/8501) and
[flugpl 8502](https://lab.eignex.com/jobs/8502) at the same revision preserve exact
accounting across both arms and every repeat. Complete checks are 63, 57 and 45;
funded work is 7,675,009, 456,528 and 635,153; modeled allocation is 496,940,
108,460 and 72,340 bytes, respectively. All available native bounds recheck, no
run stops, and no partial query executes. These zero-Boolean original sources
support unchanged complete-check accounting, with no partial-policy benefit or
throughput claim. Useful captured conflicts qualify the bounded internal consumer;
inactive-state cost and original-source applicability keep automatic activation
disabled.

### Boolean conflict locality

`MixedPbLocalityProfile` runs twelve tiny retained-projection controls for
`!b0+b2+b3+b4 >= 2`, `b2 <= b1` and `b3 <= b1`: two first-Boolean
polarities, pure Boolean/unrelated finite integer/connected finite integer, and
clause/PB learning. The connection is `b0 iff x0 <= 5` with `x0` in `0..10`.
Choosing `b0=true` then `b1=false` reaches the counting conflict. Resolving the
two gated inputs retains `!b0+2*b1+b4 >= 2`, which asserts `b1=true` after undo.
Direct finite construction excludes presolve, binary substitution and LP preparation.
The profile reports stored PB constraints and later pins with a stored PB reason,
decisions, backjumps, finite work, elapsed time and current-thread allocations.
Reflection reads existing reason identities without changing propagation. Pin
observations can repeat across commits and do not count distinct deductions.
A fresh native session rechecks each feasible point; there is no independent proof
or witness check and no corpus speedup estimate. Run once as a bounded lab profile;
broader/default admission needs a separately authorized matched study.

The initial five-pigeon/four-hole source-order test produced no stored PB on native
CI and was rejected as a consumption control. Lab 8851 failed in source compilation
before executing that control. These outcomes establish no locality rejection.

[Lab 8857](https://lab.eignex.com/jobs/8857) completes all twelve controls at
`02b9fe7ab2bd2672ec8246a71cf23ef711f208cf` on Linux amd64, Temurin
`25.0.4.1+1-LTS`, with a hard cgroup budget of one vCPU and 4608 MiB. All twelve
feasible points pass fresh native-session checks. Each conflict-triggering PB arm
stores one PB and observes its reason after backjump; the clause arms store none.
Decisions and backjumps match within each projection. Finite work rises from
76 to 111 (Boolean), 76 to 121 (retained integer) and 106 to 151 (connected integer).
The two mixed triggering pairs allocate roughly 150–166 KiB per solve including
preparation and native point recheck; the first Boolean arm includes class loading.
These single ordered runs supply no timing advantage or practical-admission result.
Mixed automatic admission remains disabled. Raw records, JFR, distribution hashes,
resource receipts and the failed attempt are retained outside Git under
`klause-evidence/campaigns/finite-conflict-learning-3727-3728/` beside the primary checkout.

### Finite integer conflict rows

`IntegerRowLearningProfile` compares clause and explicitly enabled integer-row
learning with PB disabled on the retained finite projection `2*x+y<=z`,
`2*x-y<=0`, `x=0..6`, `y=0..12`, `z=0..24`. It reaches the same conflict at
`z<=7`, `x>=2`, applies the shared/native backjump contract, then reuses the store
after undo at `z<=11`, `15` and `23`. The row `4*x<=z` asserts `x<=1`
and tighten sibling upper bounds to `2`, `3` and `5`; individual rows reach
`5`, `6` and `6`, and the original endpoint clause is inactive there.

The eight records retain actual row reason identities, native/shared store counts,
cumulative finite work, total elapsed/current-thread allocations and explicit
feasible source points checked in fresh native sessions. There is no independent
witness or exported-proof check. Construction deliberately retains the finite
projection without presolve or LP: difference extraction excludes the three-term
and non-unit coefficient shapes, binary substitution requires Boolean domains,
and LP/OBBT can discharge the inference. This applicability control establishes no
advantage over preparation or default admission. Later qualification must compare
consumed reuse and full costs with ordinary CP, normal presolve/LP and clause controls.

[Lab 8863](https://lab.eignex.com/jobs/8863) completes all eight records at
`1ce848b6e06d77f86ffc8569ff601c9b40b92b76`, with PB disabled. One stored row owns
the original assertion and all three later sibling deductions; the clause arm
retains no row and reaches the weaker sibling bounds above. All eight emitted
source points pass fresh native checks. The Linux amd64, Temurin
`25.0.4.1+1-LTS` execution has a hard cgroup budget of one vCPU and 4608 MiB.

Final cumulative native finite work is 233 for clauses and 370 for row learning;
native store counts are 8 and 12, including channeling clauses, and shared stores
remain empty. Cumulative elapsed/current-thread allocation checkpoints include
preparation, point rechecks and prior record writing. The first clause arm includes
class loading: its final checkpoint is about 389 ms/10.24 MB versus 20.5 ms/0.658 MB
for the subsequent row arm. Ordered warmup and overlapping cumulative checkpoints
prevent any timing/allocation advantage claim; do not sum checkpoints. No normal
presolve/LP comparison or broad qualification was run. The experimental flag stays
disabled by default.

Initial native CI exposed a failed shared-scope backjump mapping in the direct
controls; the production traversal control passed, and the corrected mapping passes
both routes. Job 8861 was cancelled during preparation after that finding and
executed no cases. Raw records, source points, hashes, JFR, resource receipts,
CI reports and the cancelled attempt are retained outside Git under
`klause-evidence/campaigns/finite-conflict-learning-3727-3728/` beside the primary checkout.

The sparse [8905 gate](https://lab.eignex.com/jobs/8905), at
`bab7a828da2a89911c7647f3ba131f3270094402`, repeats the four endpoints with
clauses and rows on contiguous domains, `y!=2` and `y!=4`: six sessions, 24
endpoint records and six disposal records. With the incidental hole at 2, one
retained row owns `x<=1` and sibling bounds `2`, `3`, `5`; clauses retain bounds
`5`, `6`, `6`. Sparse membership survives undo. The hole at 4 is clipped by
`y<=3` in this source order and retains clause fallback. Reversed-row CI also
covers the requested lower bound 4 snapping to 5. Historical target holes, later
carves, signed endpoints and the 64-position sparse-tail allowance have separate
controls. No domain is replaced by its hull.

All 24 source points pass fresh native checks; no independent witness or exported
proof check is supplied. At the last incidental-hole endpoint, cumulative native
finite work is 361 for rows and 233 for clauses, plus 48 point-check work units in
each arm. Stores contain 12 and 8 native constraints before disposal, including
channeling clauses; every native store is empty after disposal and shared stores
stay empty. Elapsed/current-thread allocation checkpoints include construction,
checks, earlier records and disposal. Ordered class loading and overlapping
checkpoints support no timing or allocation advantage. The Linux amd64 execution
uses Temurin `25.0.4.1+1-LTS`, one hard cgroup vCPU and 4608 MiB.

Job 8885 retains a failed historical-hole admission prediction and partial records;
8905 also retains its infrastructure retry before execution. These limits leave
integer-row learning opt-in, without a preparation/LP or default-performance claim.
Raw records, source/build hashes, JFR, receipts and CI reports live under
`klause-evidence/campaigns/integer-provenance-3753-3754/` beside the primary checkout.

### Retained integer reason gate

`IntegerReasonReroutingProfile [m]`, with `m=2..32` and default 8, compares all
six source orders of `m*x+y<=z`, `x-y<=0`, `z-m*x<=w`. Clauses, original-row
learning, an actually acquired eager row `(m+1)*x<=z`, and a numeric surrogate
visit the conflict `x>=1,w<=0`, then siblings `w<=1` and `min(3,m)`. The surrogate
supplies the redundant row as an original source and omits its acquisition costs.
Optional arithmetic tracing adds bounded pre-undo seed owners, markers, derived
rows and consumers; absent tracing is explicit, with no substituted owner claims.

[Gate 8921](https://lab.eignex.com/jobs/8921), at
`169180d956d16e52f4eede31176efab2846e3b76`, completes 24 sessions and 48 stage
records at `m=8`, with no truncated traces. Original rows retain `z<=9*w` in three
orders and `y<=w` in the other three, then own deductions that give `x<=1` and
`x<=3` at both siblings. Clauses alone stop at `x<=8`. In source order A/B/C,
the failed A row resolves B then C; the numeric surrogate resolves C from the
retained-row shape and produces the same `z<=9*w`, rather than `x<=w`.

Cumulative native work is 826–901 for original rows, 1249–1701 for the acquired
eager row and 802–975 for the surrogate. The surrogate saves at most 41 units in
three orders while omitting 173–182 extra units of actual acquisition; it costs
more in the other three orders before acquisition. Point checks add 38 units per
original/eager session and 48 per surrogate. Native stores contain 28–29, 48–69
and 25–30 learned constraints respectively before disposal; the surrogate also
carries one extra base row. Every native/shared store is empty afterwards.
Ordinary original-row rerouting discharges this scaling lead,
so recursive numeric reason storage is not adopted from it.

All 48 source points pass fresh native checks, without an independent witness or
exported-proof checker. Host-auto placement selected Mac arm64, Temurin
`25.0.4.1+1-LTS`, with advisory one-vCPU/4608-MiB budgets. Ordered elapsed and
current-thread allocation include construction, tracing, checks, earlier record writing
and disposal, and supply no throughput claim. Job 8901 retains an incorrect
uniform-sibling-bound prediction; 8919 failed trace-hook compilation before cases.
Hashes, complete captures, JFR and receipts remain beside the primary checkout in
`klause-evidence/campaigns/integer-provenance-3753-3754/`. This bounded rejection
does not establish that every retained-row family lacks useful numeric reasons.

### Guarded integer conflict rows

`GuardedIntegerRowProfile` keeps the finite reified source `r iff 2*x+y<=z`
(and its negative-indicator equivalent), with mandatory `2*x-y<=0` and domains
`x=0..6`, `y=0..12`, `z=0..24`. Clause and opt-in row arms acquire a conflict at
`guard,z<=7,x>=2`, then retract the guard and reuse at `z<=11` and `15`.
The row must own the sibling bound reason; false guards retain a feasible region.
One warmup per configuration precedes one measured lifecycle. Native point rechecks
are separate from independent source-witness or proof verification.

Each lifecycle records construction, acquisition, cumulative native work, constraints,
current-thread allocation, total elapsed time and explicit store disposal. Cumulative
checkpoints overlap and must not be summed. Conditioned-root controls capture ordinary
solution-set-preserving preparation and the existing exact-certified LP relaxation
with objective `-x`, a ten-second containing deadline and 200,000 floating work units.
They retain prepared factor/domain shapes and establish only conditional authority.
These instrumented controls do not qualify defaults or establish whole-CLI throughput.

`producer=element` selects the independent variable-array control
`r=Element([a,b],i)`, `2*x+a<=z`, `2*x-r<=0`, with `i=0..1` and the same numeric
boxes. Only a selected equality's bound conflict seeds arithmetic learning; support
reasons retain clause fallback. The profile requires an index-guarded row to own
sibling deductions and keeps the feasible `i=1,x=2,a=0,b=r=4,z=4` alternative.
Ordinary controls include default and aggressive solution-set-preserving preparation
with separate one-million-work allowances, and the existing Element LP hull under
root conditioning. Neither root preparation nor its hull creates an unconditional
selected equality in the retained search session.

[Element gate 8914](https://lab.eignex.com/jobs/8914) completes fourteen records at
`13db8c402932298b73d19c8bcce5fa976ca952a8`: four lifecycles including warmups and
two ordinary conditioned roots. One retained row owns both measured sibling bounds,
`2` and `3`, versus clause bounds `5` and `6`. Twelve fully fixed source points,
including the other-index alternative, pass fresh native checks. These are finite
API controls without independent source-witness or exported-proof checking.

At the second measured sibling, native work is 295 for clauses and 466 for rows;
stores contain 8 and 11 constraints, including channeling clauses, with one retained
integer row and no shared clauses. Complete measured lifecycle allocation is
323,328 versus 338,688 bytes, and elapsed time is 9.82 versus 9.74 ms, including
construction, acquisition, point checks, record writing and disposal. Disposal leaves
both stores empty and costs about 0.11–0.12 ms. One ordered sample under JFR supplies
no timing advantage claim. Default conditioned preparation leaves `x` in
`{0,1,2,4}`; aggressive preparation reaches `x<=2`, and the existing Element hull
certifies objective bound `-11/4` for `-x`, hence the same integer bound. Preparation
spends 163,598 and 403,657 work units. This establishes guarded retained consumption,
not superiority over ordinary preparation or LP, and keeps defaults unchanged.

[Reified gate 8909](https://lab.eignex.com/jobs/8909) independently completes
twenty-eight records at the same revision: eight lifecycles including warmups and
four ordinary conditioned roots. Both signed guards retain one row that owns the
sibling bounds `2` and `3`, versus clause bounds `5` and `6`. All twenty-four fully
fixed source points pass native checks, including assignments violating the
unguarded row when its guard is false. At the second measured sibling, native work
is 294 for clauses and 395 for rows; native stores contain 7 and 9 constraints and
shared stores remain empty. Complete lifecycle allocation is about 283 KB versus
294 KB. Ordered elapsed observations are 6.51–6.71 ms versus 6.04–6.33 ms; disposal
leaves no stored constraints and costs 0.065–0.090 ms. These observations supply no
statistical speedup or default-admission claim.

Default conditioned preparation retains `x` in `{0,1,2,4}`; aggressive preparation
and the exact LP both reach `x<=2`. Preparation spends 148,868–148,888 and
186,779–186,783 work units. The reified and Element executions both use Linux amd64,
Temurin `25.0.4.1+1-LTS`, and hard cgroup budgets of one vCPU and 4608 MiB. Source
bytes, revision/build identities, complete records, JFR and runtime/preparation
resource receipts remain outside Git in
`klause-evidence/campaigns/guarded-integer-rows-3752-3755/` beside the primary checkout.
Job 8899 failed profile compilation before any case ran; no local builds, tests or
numerical work ran. Neither gate supplies independent source-witness or proof checking.

### Prepared arithmetic learning replay

`PreparedArithmeticLearningProfile` freezes the two source shapes from the direct
PB and integer-row controls as OPB and FlatZinc bytes. It compares the learners
independently, with the other learner disabled. Twelve cells distinguish retained
finite conflict replay, normal finite preparation followed by the identical replay,
and production finite solving with normal preparation and automatic LP. The LP
route answers whether the strong ordinary owner already discharges the source;
its changed search trajectory is separate from same-conflict deduction comparisons.

The fixed conflict script runs only when lossless canonical and prepared model
identities match. Rewrites without a qualified coordinate mapping are an explicit
replay decline, never a guessed source-variable remap or evidence of universal
inapplicability. Records retain original bytes/hashes, canonical and prepared JSON,
preparation statistics, conflict/fallback/native/shared levels, historical endpoint
markers and holes, arithmetic store identities, actual later pins, and truncation.
Normal preparation may use its ordinary probing/LP passes; capture adds no solve.
Both learners retain their existing default admission policy.

Each cell has two warmups and two measured invocations, with the second pass in
reverse order: 48 invocations, at most two seconds each, in one lab profile capped
with `profile.runTimeoutSec=180`. This is a bounded diagnostic, not a corpus gate. Complete disjoint
intervals include parsing, preparation, session construction, analysis, search or
script replay, original-point checking, release and capture serialization. Record
file writes are outside the interval. Traces remain optional; warmed JFR timing and
current-thread allocation include their overhead and establish no uninstrumented
throughput or physical memory advantage. Releasing references does not time future
GC. Warmups, failed attempts, skips and preparation changes remain in the records.

Script points are explicit feasible original-source controls, separate from search
witnesses. Production points are reconstructed to original coordinates before a
fresh native point check. Neither route has an independent witness or exported
proof checker. Do not add cumulative checkpoints or infer consumption from storage
alone. Review PB and integer costs independently; no prepared consumer or losing
total work/storage rejects practical admission for that selected source, without
claiming a broad negative or enabling a default. Raw records belong under
`klause-evidence/campaigns/prepared-arithmetic-3757/` beside the primary checkout.

The internal trace defaults to 128 events, 8192 scalar words and at most 64 tracked
arithmetic constraints. A row endpoint records variable, coefficient, historical
minimum/maximum, historical hole count and live lower/upper reason factor ids;
following hole events name the excluded values. Each row names its signed guard,
with a separate Boolean/order-atom tuple; `Lit.NONE` records unconditional rows.
Raw endpoint markers retain their
original trail boundary and payload. Clause tuples record literal, cached level,
integer coordinate, atom-kind ordinal and threshold (Boolean tuples use the reason
factor id in the last slot). `analysis.seed-level` carries the effective level
already computed by analysis. Derived rows/PBs and clause fallback stay distinct.
`analysis.mapped-target` gives requested native target, shared target and actual
native level at application. Store events assign a trace-local stable id; consumer
events carry that id, current factor, coordinate, bound/value and native level.
Remap events retire or move the same id. Truncation stops all subsequent capture;
missing events after it cannot be interpreted as a decline or lack of reuse.

[Prepared replay 8900](https://lab.eignex.com/jobs/8900) completes all 48
invocations at `8f0f7b8d330bf6e8d3c545937c855ed8e9e1cb3b`. The returned OPB
`cc9d2968347e` and FlatZinc `30eaca5a4886` hashes match the statically selected
bytes. All 48 original-point checks pass, including sixteen reconstructed production
witnesses; there is no independent witness or exported-proof check. No invocation
is cancelled and no retained script trace is truncated. Automatic placement chose
Mac/aarch64, JDK `25.0.4.1+1-LTS`; the one-core/4608 MiB resource receipt is advisory,
not a hard cgroup limit. Original bytes, complete hashes, runtime/distribution
fingerprints, commands, JFR and receipts remain outside Git in the campaign above.
The client request supplied a 180-second base timeout; the execution receipt has a
300-second outer profile limit and 1.533-second command elapsed. An explicit outer
profile cap uses `profile.runTimeoutSec` rather than the base solve timeout.

| Source | Retained conflict replay | Normal prepared source |
|---|---|---|
| Boolean PB | One PB owns the asserting pin; native work rises from 44 to 78. | Structural normalization, dual fixing and symmetry add `b0=false`, `b1=true`, `b4=true`. The trigger is removed. Ordinary LP-enabled solving takes two decisions, 45 finite work and three LP solves in both arms, with no learned constraint. |
| Integer rows | One row owns assertion and the later `z<=11` deduction `x<=2`, versus clause control `x<=5`; native work rises from 207 to 261. Both stores have four entries, of different kinds. | Singleton-column, dual-fix and strengthening preparation removes both rows and fixes `x=0`. Both ordinary arms finish with zero decisions, six finite work and no LP solve or learned constraint. |

The two warmed retained PB clause intervals are 0.793–0.817 ms and 133504
current-thread allocated bytes; PB intervals are 0.890–1.126 ms and 151704–215392
bytes. Integer clauses take 1.348–1.654 ms and 164184–166336 bytes, versus
1.405–1.732 ms and 196160 bytes for rows. These disjoint complete in-process
intervals include capture and source-point checking. Two instrumented observations
per arm supply no uninstrumented throughput or physical-memory conclusion; JVM
startup and future GC are outside the intervals. The normal changed trajectories
are separate controls, not same-conflict timing pairs.
Preparation has no separate deterministic work meter here; its time and allocation
are included in the invocation totals.

This gate rejects practical/default promotion on these two SAT source controls:
normal preparation removes the retained opportunities, and the direct controls
supply no total-cost advantage. It does not reject arithmetic learning universally,
undo the delivered opt-in capability, change pure-Boolean PB admission, or determine
the separate guarded, Element, hole or recursive-row hypotheses. No broader source
campaign is required by this negative. Failed lint checks and the seed-hook compile
correction are retained with their CI logs; they executed no accepted new replay.
