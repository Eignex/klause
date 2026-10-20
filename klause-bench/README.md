# klause-bench

JVM catalog, corpus management, subprocess runner and offline measurement tools.
This README owns benchmark usage, records, validation and diagnostics. Solver
contracts live in [docs](../docs/README.md). Committed fixture identities and
licenses live in the README beside each fixture directory, or in
[fixture provenance](#fixture-provenance) for fixtures without one.
Commands run from the repository root unless shown otherwise.

| Task | Read |
|---|---|
| Run or select cases | [Build](#build-and-run), [selection](#selection-and-named-sets), [corpus](#catalog-and-corpus) |
| Run remote experiments or regression schedules | [Klause-lab](#klause-lab) |
| Identify and compare measurements | [Provenance](#installed-build-provenance), [records](#saved-results), [comparisons](#comparing-configurations) |
| Check source claims | [Validation](#source-validation) |
| Investigate cost | [Profiling](#profiling), [focused probes](#focused-probes), [LP replay](#lp-capture-and-replay) |
| Review retained design decisions | [Design evidence](#design-evidence) |

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
or newer. Gradle provisions its own compilation toolchain, so a shell whose default
`java` is older fails at run time with `UnsupportedClassVersionError`; point
`JAVA_HOME` at the provisioned JDK, for example
`JAVA_HOME=$(ls -d ~/.gradle/jdks/*-25-*/ | head -1)`. The installed bench
launcher uses `klause-bench` as its working directory. `-Dklause.*` properties are
forwarded to the bench JVM. Keep the CLI distribution and runtime fixed during a run.

| Command | Purpose |
|---|---|
| `list [suite]` | List suites or problems |
| `preview [filters]` | Inspect selection without solving |
| `select [filters]` | Emit instance identities as JSON lines; `features=true` adds structural features |
| `solve [filters]` | Run one configuration over the selection |
| `solve-one suite=<id> problem=<name> [filters] [out=<dir>]` | Run one selected identity and save a case record |
| `validate-solution suite=<id> problem=<name> output=<path>` | Check saved MiniZinc, XCSP3 or SMT output without solving again |
| `provenance out=<path>` | Capture installed-build and runtime identities |
| `corpus status`, `corpus gc`, `corpus compress [path]` | Inspect, evict or compress fetched collections |
| `mine [by=config\|suite\|family\|format\|category\|kind] <cases.json> ...` | Report portfolio holder share, set cover, work and rewards; retain quarantined cases |

`com.eignex.klause.bench.profile.LinearSourceCapture` records direct-library
`LinearSourceProblem` invocations. Supply one base64 JSON array with original MPS
bytes and SHA-256, declaration-order rational variables/rows/objective, tolerance,
engine and limits. Fractions use integer or `numerator/denominator` strings.
The profile retains input/runtime/build identities, original bytes, every checked
source point, original objective value and separate native/source conclusions.
Optional `nodeLimit` supplies the finite invocation's shared decision/move allowance;
`work` retains its arithmetic meaning. `openBoundProof=false` declines only optional
bound closure. Measurements retain these controls, original/working coordinate
counts and the actual scheduled worker labels and segments.
The supplied neutral descriptor is a caller construction, not a certified MPS
translation. Point/reference agreement does not verify exported native certificates
or establish accepted-set completeness. Run through the maintained lab client at
a pushed revision with `host=auto`; retain the actual host/runtime provenance.

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
| `timeout=<ms>`, `label=<name>` | Per-instance budget and optional configuration tag |
| `solver-seed=N` | CLI seed (`-r`); unset uses the harness's fixed seed |
| `engine=fixed\|cp\|mixed\|ls`, `processors=N` | Forward CLI `-e`/`-p`; unset engine uses the CLI default |
| `lp=off\|conservative\|default\|aggressive[±id…]` | Forward the CLI LP ceiling and technique deltas |
| `exact=true\|false` | Forward `--exact`; exact/default results and cache keys remain separate |
| `param=key=value` | Repeatable CLI engine parameters; unknown/malformed keys fail at the CLI |
| `profile=cpu\|wall\|alloc`, `profile-scope=solve\|all`, `profile-top=N` | Opt-in JFR diagnostics |

Settings, budgets, non-default solver seeds, parameters and labels distinguish
configuration directories. Run configurations separately to compare them.
`param=source-validation=true` opts MiniZinc integer, XCSP3, MPS and SMT-LIB runs into independent
source-witness checking and is consumed by the bench before CLI launch.

The `divmod` suite retains self-authored scalar, affine wide, equivalent linear and
joint-conflict sources, plus signed MiniZinc and XCSP3 inputs. The adjacent FlatZinc
fixture supports direct CLI captures. MiniZinc and
XCSP3 runs use `param=source-validation=true` for original-source witness checking.

`mps-linear-source-v1` retains self-authored decimal mixed rows, enabled/disabled
indicators, shared contradictions, constant objectives, bound closure, wide numbers
and duplicate decimal terms. Compare default and `exact=true` configurations at
pinned revisions; this fixture suite requires hash-named source bytes.
Reference agreement does not verify an exported solver proof certificate.

`bench.profile.LpCertificationAdmissionProfile` measures exact basis verification and
continuation-target dimension rejection without a floating solve. Submit it as a
pinned lab profile with `rows=129 attempts=20000 rounds=7 warmup=5000`, one machine
and one measurement thread. It saves actual JVM processor visibility, installed CLI/runtime,
classpath and compiler hashes, and alternating per-mechanism elapsed/thread-allocation
observations. A requested lab core count need not constrain profile JVM processor
visibility. It requires a dimension decline with one entry work unit and no modeled
allocation. Reflection retains the numerical engine's private API. Compare identical
profile source, settings and runtime identities across revisions; these setup timings
and allocations do not establish whole-source solve speedups. Use uninstrumented,
source-checked default/exact experiments separately.

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

Leave `host` out: the lab then runs an experiment on its Mac when that is free and
on AWS otherwise, spot first and on demand after a few minutes (`auto`). Name `aws`
or `lab` only when the host matters to what you measure, such as timings you will
compare with earlier jobs on that host; the example above pins `lab` for that reason.

`problems` takes a selection map or a list of maps, each naming `suite` or `set`.
`base` is overlaid by each `configs` entry and crossed with optional `grid` axes.
Configuration values are strings; `param.<name>` becomes CLI `--param <name>=<value>`.
For Boolean objective core-search diagnostics, `engine=cp` with
`param.bt-arm=core-guided` and `param.arms=1` isolates the retained worker.
Use separate default-route matched runs to establish portfolio gains.
Use immutable commit IDs for reproducible revision comparisons. Selection reference
filters can change membership; `reference=any` keeps the benchmark selection, and
named sets default to that policy. Record the actual planned inputs and hashes.

From the separate `klause-lab` checkout, using its configured client. The lab needs its
password on every call: the client reads it from `~/.config/klause-lab/password` (or
`LAB_PASSWORD`), so never pass it on a command line. Off the lab's home network, set
`LAB_URL=https://lab.eignex.com`.

```sh
./deploy/lab run /absolute/path/to/klause-evidence/campaigns/lp-ceiling/experiment.json
./deploy/lab show 123
./deploy/lab cases 123
./deploy/lab csv 123
./deploy/lab files 123
./deploy/lab cat 123 job.log 8000
./deploy/lab fetch 123 /absolute/path/to/klause-evidence/campaigns/lp-ceiling/raw
```

Submit one experiment per question, covering all its problems; never split it into
batches. The lab bounds runtime itself (per-case timeouts, profile time limits) and
places work on the Mac or AWS by queue pressure, so impose no wall-clock caps of your
own and do not cancel a job to keep it short. Follow a long job with `lab wait <id>`
run in the background; `lab cases <id>` shows partial results while it runs. The lab
refuses (409) a submission whose arms, seeds and repeats match a queued or running
experiment's and that differs only in name, description or problems.

Replace `123` with the returned job ID. `lab cases` prints a tabular summary;
the HTTP `GET /experiments/<id>/cases` endpoint supplies the JSON array consumed by
`bench mine`. `lab fetch` downloads records and raw
outputs, including `cases/<index>/record.json`.
`lab compare` links experiments; `lab schedule`, `reschedule`, `schedules` and
`check` manage regression runs under the lab's operations guide.

Exact-linear records distinguish `smtRealGraphOpenings`, completed primitive
table captures (`smtGraphPointHintCaptures`, `smtLpPointHintCaptures`) and
`smtDefaultGraphHintReleases`. The capture counters name the existing point's
producer path, including LP fallback in a graph-first opening. A completed table
can contain only declined preferences. Release counts a live default consultation,
not avoided registration or capture work. These observations add no numerical
invocation and establish neither a timing cause nor a whole-source witness gain.

`lab files` lists a job's files and `lab cat <id> <path> [tail-bytes]` prints one, such as
`job.log`, `setup.log` or `cases/<index>/record.json`, over HTTP: reading a job needs no SSH.
`lab ssh <id> [instance] [command]` reaches a running AWS job's instance. Measure as a lab
experiment rather than directly on the lab Mac, where a run competes with the lab's cases.
A measurement no experiment expresses, such as timing a component on captured inputs, runs as
a profile job: `lab profile <main> <ref> <description> [aws|lab] [timeout-sec] [arg...]` runs
the class `<main>` (directly in `com.eignex.klause.bench.profile`, committed at `<ref>`) once
under JFR, on the lab Mac when it is free and on AWS otherwise, and keeps its output and whatever it writes to `$OUT` with the job; from AWS at most 1 GiB
of `$OUT` comes back, smallest files first, with the rest listed in `$OUT/omitted.tsv` and lost, so
write summaries rather than raw dumps.
The lab runs no shell scripts: the code goes in a commit. Profile jobs are not for tests, lint or
other gates; CI runs those. Every ref the lab builds must be `main` or a branch only trusted users
pushed to, and an experiment may not set `suite`, `problem`, `out` or `solver-seed` itself.

[SolveTiming](src/main/kotlin/com/eignex/klause/bench/profile/SolveTiming.kt) is the template for
a new profile main. It solves one catalog problem `repeats` times in-process and writes each run's
wall time and verdict to `$OUT/timings.json`; without arguments it times `opb-core`
`sporttournament06` three times:


```bash
./deploy/lab profile com.eignex.klause.bench.profile.SolveTiming main "solve timing" aws 600 \
  suite=dimacs-core problem=random3sat-50-200 repeats=3 timeout=10000
```


`bench.profile.NValueNeighborhoodProfile` obtains a CP incumbent, then measures bounded
structured proposals for each original NValue factor with per-move definitions disabled
and enabled. Use `suite=<id> problem=<name> seed=17 timeout=10000 calls=32`; the timeout
applies to incumbent acquisition and calls is capped at 256. It retains the incumbent,
installed-build provenance, proposal time/allocation totals, maintained input counts and
the residual factor kinds and clause dependencies of proposed cardinality reductions under `$OUT`.
For a reduction with remaining violations, a separate diagnostic state traces up to two
ordinary clause repairs while protecting every NValue coordinate and the count. The
trace does not change the measured state's assignment or random stream. The
diagnostics also invoke the internal scratch-completion component through its JVM
constructor ABI on a separate state and record the resulting parts and full residuals.
For up to 16 remaining Element factors it records the selected cell, coordinate domains
and definition ownership, then grades up to eight ordinary repairs with the completed
primitive writes pinned. It also records up to 16 remaining reified linear rows, their
indicator maintenance and pins, coefficients, values and root domains, and grades up to
eight ordinary repairs per row with the same completed writes pinned.
These repair observations use only the separate diagnostic state.
An ABI mismatch fails the probe instead of silently skipping that component.
Proposal generation must leave the incumbent unchanged. Scoring and incumbent acquisition are
outside proposal timing. This probes the neighborhood at a captured feasible point;
it does not measure portfolio reachability, independent source validity or end-to-end gains.

`bench.profile.PreparedPlanProfile` measures immutable preparation and objective binding
separately for finite, open-linear, shared/native polynomial and nonlinear-integer
sources. Use `iterations=N repeats=N warmups=N`; defaults are 1000 operations,
seven measured rounds and three warmups. The profile saves each batch's elapsed
nanoseconds and current-thread allocated bytes, source descriptions, admission and
coordinate observations, actual JVM facade descriptors and runtime/classpath hashes.
Run the identical committed profile at both revisions with matching JVM/core settings.
These are in-process preparation costs under JFR, not solver verdicts or proof checks.

`bench.profile.CompletionInitializationProfile` measures actual internal repair consumers
without adding a published library debug API. It generates a hybrid finite-domain and
scaled dense-linear source, alternates fresh/retained owners, cancels root installation,
and checks the final exact source point with the configured Z3 executable. Use
`rows=N attempts=N rounds=N cancel=early|charged`; defaults are 128 rows, 64 cancellation
attempts and five measured rounds after warmup. `charged` stops after native LP work
reaches the invocation meter; `early` stops at a fixed initial poll. A completed search
must not be retained after an interrupted attempt. Native preparation charges are recorded separately from LP interruption charges.
On Linux x86-64, an unset `klause.bench.z3` provisions the pinned Z3 4.15.3.0
executable from its SHA-256-checked wheel before measurement; another platform
requires an explicitly configured reference executable. The tool writes source/pinned SMT,
raw measurements and checker output under `out=DIR` or `$OUT`, and prints runtime,
loaded library/tool jar and reference binary identities. Source checking and setup
are outside the measured query spans. JFR instrumentation remains part of profile jobs;
these measurements are preparation diagnostics, not ordinary end-to-end solve timings.

`bench.profile.LinearRootOpeningProfile` inspects an original catalog source's
direct-library root opening without advancing its traversal. Pass
`suite=ID problem=NAME sha256=HEX`; it verifies and saves the decoded source bytes,
then runs with a ten-second opening deadline and seed 1 in a one-processor child JVM.
Parsing is timed separately before that deadline. The output records the raw decided
reason or live root counters, deadline timing, runtime/build identity and child
command, streams, exit and transport status. The parent reaps a terminated child;
its separate transport limit does not extend the opening deadline. A running root
is explicitly closed. This diagnostic claims neither CLI routing equivalence nor a
source verdict, and cannot establish a default-route recovery.

`bench.profile.LinearSourceShape` accepts `suite=ID problem=NAME sha256=HEX` and
retains the hash-matched original, build identity, declaration/lowered dimensions,
factor kinds and exact-row predicate counts. Predicate identity removes only the
activator before using `ExactLinear` structural keys; it does not reason about
signs, implications or source satisfiability. Raw coordinate/activated-row entry
counts describe the parsed source, independently of runtime fragment admission.
Parsing has a separate ten-second
deadline. This diagnostic opens no solver and supplies no route or verdict claim.
For up to eight sources, `batch64=<base64 JSON array of argument arrays>` retains
each inspection in a numbered directory.

`LinearRootOpeningProfile` with `inspect=disjunction-names` additionally replays predicate registration
from the cached preparation fragment in a fresh registry, using only the original
deadline's remaining time. It records the first refused row and any existing
complementary names under the unchanged registry limit. This is isolated naming
demand: it neither captures the actual rebound fragment nor reproduces earlier
component registrations, initializes a solver, or proves the opening's refusal cause.

`bench.profile.ExecutionOptionsProfile` compares legacy request parsing, captured frontend
options and directly typed options while repeatedly resolving the same portfolio settings.
`rounds` (default seven) alternates mode order after one warmup round; `plans` (default
10,000) sets the plans per measurement. The profile retains wall nanoseconds, thread
allocation bytes, actual revision, runtime/options, classpath hashes and resolved
seed/core/arm/slice settings under `$OUT/execution-options.json`. Run it through the
AWS lab client at a pushed pinned revision. These planning costs exclude model
preparation, worker execution and source checking; use matched whole-CLI controls
for end-to-end comparisons.

`bench.profile.PublishedAbiSnapshot` captures compiler-generated JVM and Kotlin/Native
published API declarations from the profile job's pinned checkout. It runs
`:klause:updateKotlinAbi` remotely, retains both exact dumps, the generation log and
source/hash provenance under `$OUT` (or `out=DIR`), and reports generation failure
without fabricating declarations. Use this diagnostic when local builds are
unavailable; inspect compatibility changes and commit the generated reference bytes
before checking the final CI gate.

Cases disable result caching, alternate configurations across seeds/repetitions,
and retain installed-build provenance when supported by the selected revision.
XCSP3 references accept only `cp-sat`; `solve-one backend=reference solver=cp-sat`
uses the CPMpy importer and OR-Tools engine. Unsupported solver names are rejected
before invocation and cannot supply a reference verdict under another engine's label.
The `xcsp3-source-preserving-v2` policy uses the pinned packages of the
`klause-xcsp3-cpsat:source-preserving-v2` image, built from
[klause-lab](https://github.com/Eignex/klause-lab/tree/main/images/xcsp3-cpsat).
Negative extension tables encode each forbidden tuple as a disjunction of
coordinate disequalities; wildcard coordinates contribute no comparison. This
preserves every permitted source pair after unary exclusions and avoids the
native negated-table conversion. Positive extension tables keep their native
encoding. Implicit objective coefficients remain integer ones. Native model
presolve and search remain enabled.
The image digest, importer policy, worker count and solver seed distinguish cache
entries. Records retain effective `referenceSeed`, `referenceWorkers` and
`cpModelPresolve` settings, complete original-variable `finalWitness`, raw output
identity, installed harness/runtime provenance, container exit/watchdog diagnostics
and `exactObjective`. Failed or interrupted container processes remain undecided.
Emitted `o` and `v` lines precede the final JSON
verdict. Feasible claims without a complete witness and proofs with incompatible
native settings are undecided. Native optimum/refutation statuses remain reference
claims; a source-checked witness certifies feasibility and its objective only.

klause-lab builds the image on the host providing Docker and runs its importer regressions,
which cover matrix axes, weighted expression sums, contradictions, unconstrained source
variables and objectives beyond binary64 precision.

Check failed, unsupported and missing cases before interpreting comparisons.
Lab agreement and regression confirmation remain recorded-result evidence;
[independent source validation](#source-validation) defines stronger checks.
`profileCli` requires AWS execution and explicit `parallel: 1`; its instrumented
timing stays separate from ordinary comparisons.
Its whole-CLI phase tables attribute visible stack frames to shared source
preparation, theory source installation, session opening, root initialization,
propagation, model checks and search. Presolve and legacy backtrack callers retain their outer phase.
Utility frames without a recognized caller remain unclassified. These sampled
categories describe visible operations, not exact wall time or theory-check counts.
Separate caller tables retain the first three captured non-utility Klause frames,
in stack order, for CPU samples and allocation weights. Runtime, neutral utilities
and benchmark frames are skipped; a stack without an operation frame keeps an
explicit unknown label. These paths supply sampled context for collection or
runtime self-samples, not exact elapsed cost or a causal explanation. Summarization
runs after subprocess timing and does not change the CLI recording configuration.


## Catalog and corpus

The `continuous-arithmetic` suite contains synthetic MiniZinc fixed power, square, independent
product and product-chain controls with objectives attained at exactly representable
endpoints, plus a fixed-power satisfaction control. Use repeated matched default-grid runs with original MiniZinc witness
validation for execution comparisons. Native grid exhaustion or an attained grid
objective supplies no original-source proof. Record compiler/build identities and
unchecked or declined runs separately before interpreting costs.

[Suites.kt](src/main/kotlin/com/eignex/klause/bench/catalog/Suites.kt) declares
vendored, generated, workspace and fetched inputs. Collection declarations own
license and redistribution policy. Legacy family sampling precedes resolution;
compatibility selection checks compilation while filling family caps.
`-Dklause.bench.shard=i/n` selects every nth chosen input before compilation-cache
access. MiniZinc Challenge instances with data use `year/family/data` keys.
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
seeded ordering remains deterministic. Compatibility preparation precedes sharding. Catalogue `suite` selection applies
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

The reusable remote `MiniZincCompatibilityProbe` accepts `names=<comma-separated
identities>`, `repeats=N`, `per-family=N`, `max=N`, optional `families=<comma-separated families>`
(up to 128 total identities), and `set=<frozen set>` through `lab profile`. It retains
original source bytes, build/compiler provenance, fresh alternating-order compilation
receipts, exclusion diagnostics and legacy/compatibility selections under both
head and seeded caps. Its solver invocation count is zero; it measures compilation
and selection cost separately from solver outcomes.

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

## Fixture provenance

Fixtures with a directory README record their identities and licenses there:
the [smoke corpus](smoke-corpus/README.md) and the
[basis corpus](../klause/src/jvmTest/resources/basis-corpus/README.md). The
remaining committed fixtures are recorded here.

### XCSP3 source checking

The self-authored Apache-2.0 [region-counts-tiny.xml](src/test/resources/xcsp3/region-counts-tiny.xml)
combines grouped Boolean membership sums with variable conditions, region-size
vector lookup and starred extension tuples. Complete-search tests check returned
named witnesses against the original XML. The contradictory count variant requires
four region memberships across three cells, while every cell belongs to exactly one
region. It exercises source lowering and complete search without a wall-clock budget.

The self-authored Apache-2.0
[reified-hole-tiny.xml](src/test/resources/xcsp3/reified-hole-tiny.xml)
ties a disequality indicator to a table that can remove its target without moving
the domain bounds. A branch with that hole conflicts with the opposite indicator.
After rollback and learning, a source-checked assignment using the restored target
must remain feasible.

### XCSP3 reference importer

The cp-sat reference image and its self-authored importer regression fixtures live in
[klause-lab](https://github.com/Eignex/klause-lab/tree/main/images/xcsp3-cpsat), which builds and runs
the reference images. Full fetched XCSP sources and saved witness records remain external; their source
hashes and independent checker receipts bind remote regression controls to original bytes.
Source-preserving reference records retain the installed image/importer identity, complete source
assignment and exact integer objective. See the [benchmark reference workflow](#klause-lab) for the
distinction between source witnesses and native proof claims.

### FlatZinc witness

[voltage_divider.fzn](src/test/resources/flatzinc/voltage_divider.fzn)
is Hakan Kjellerstrand's `minizinc/voltage_divider/voltage_divider.mzn` from the
Hakank corpus at revision `cfdfb67f9a22836ab6b9de0ee3940c6947742bec`,
compiled with MiniZinc 2.9.7 and Klause's globals library. The MIT notice remains
in the fixture. Local-path
comments were removed. All 15 original flattened predicates and six scalar range
bounds remain. The test checks exact witness coordinates without solver lowering.
Source SHA-256 is
`ab64d949341580bf5ea6c556e6d708b2285f40593f0de49b881f15869aa4d88e`;
fixture SHA-256 is
`e8353427783116d2980f3b0c05579d493fb072e92c18d38008fec458c3f840f7`.

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

Ordinary subprocess records retain `stderr` as a prefix of at most 16 Ki characters,
with line endings normalized to newlines. `stderrComplete=true` means the drain
finished and the whole stream fits in that prefix. Null text or a false flag leaves
complete diagnostics unavailable, including older cached records. Successful
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

Each problem writes `<problem>.out` and `<problem>.json` under ignored
`output/<config>/`. Records retain verdict, objective direction, proof status,
statistics and portfolio attribution. `solve-one` writes load failures too:
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
path does not recapture changed inputs or installations. Reference failures retain
the build identity only when the reference runner supplied it before failure.
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

Outputs are staging locations. Store active notes, scripts, manifests, records
and reports in the shared external `klause-evidence/campaigns/<name>/` directory
alongside the primary checkout. All worktrees share it. Promote reusable tools,
fixtures and architectural conclusions, then delete obsolete campaign bundles.
Do not commit raw or generated archives, and do not add campaign GitHub workflows.

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

### Original corpus source capture

`output/capture-sources.sh <out-dir> < requests.tsv` exports original archive members byte for
byte without invoking a source parser, solver or checker. Each request line is
`<archive url><TAB><member path><TAB><expected sha256>`; the archive URL and member layout of a
catalog suite are its `sourceLayout` collection in [Suites.kt](src/main/kotlin/com/eignex/klause/bench/catalog/Suites.kt).
Every HTTPS archive is downloaded once (256 MiB cap, no redirect off HTTPS) and only the requested
members are extracted with GNU tar and `zstd`; tar refuses absolute and `..` member names.
`capture.tsv` records each member as captured, mismatch or missing with both digests, and
`archives.tsv` the downloaded archive digests. The exit status is nonzero unless every member was
captured. Keep requests and captured sources outside the repository. Hash equality establishes
original-byte custody, never source satisfiability.

### Prepared MPS primal probes

`bench.profile.MpsPrimalProbe` accepts original MPS paths and compares the public
`BacktrackSolver.minimize` API with `LpPlan(bounding=true, lbTreeSearch=false|true)`.
Rounding and pumping keep their plan default (`false`); this isolates explicit
library enablement and does not represent the curated CLI recipe. Both variants
use the finite MPS lowering and require exact source preservation, with the ordinary
source-tolerance callback in default mode and no callback in exact mode. Open integer declarations
are refused rather than boxed or routed to an open theory.

A private JVM uses one active processor, Serial GC and `-Xmx3g`. After one warmup
round, three measured rounds alternate variant order, with seed3, `IndomainMin`
and an unchanged five-second invocation budget. Parsing and baking are outside
the prepared API timing; root and numerical work remain inside its normal allowance.
Run through the AWS lab client on a pinned revision, one machine and `parallel:1`.
The self-authored `mps-linear-source-v1/primal-point-{min,max,wide}.mps` fixtures
exercise original sense, constants, coordinates and many integral LP columns.

The profile retains original bytes/hashes, installed build and loaded classpath
hashes, runtime/options, child process exit, measurements and every incumbent in
`cases.json`. Each record is a witness-only publication, independent of the native
terminal result recorded in `measurements.json`; no native proof is captured.


### SMT reference records

`solve-one backend=reference solver=z3` sends original SMT-LIB directly to Z3,
including QF_NRA; Klause frontend support does not control reference selection.
The executable resolved from `PATH` (or `-Dklause.bench.z3`) is hashed once per
bench process. Its version/SHA-256, memory ceiling, single-thread options and
`smt-reference-v3` policy form `referenceVersion` and the reference cache key.
Input paths use the explicit `-file:` option, preserving literal filenames containing `=`.
`-model pp.decimal=false` requests exact rational and algebraic `root-obj` models.
Input options may override printing; a decimal approximation is not algebraic
witness evidence. Records retain original `sourceHashes`, stdout SHA-256,
`finalWitness`, command, exit code, stderr, watchdog outcome and
`referenceStatus=sat|unsat|unknown|timeout|error`. Parse errors or failed exits
cannot earn a decided verdict. The default memory ceiling is 2048 MiB; override
with `-Dklause.bench.z3.memoryMb`. The solver timeout uses whole seconds, rounded
down with a one-second minimum; a watchdog kills after twice the requested budget
plus twenty seconds. Keep integer-second budgets for matched reference runs.

Z3 case `buildProvenance` also binds the installed bench launcher/libraries in
`harness`, the JVM runtime and options, host OS/architecture, and the installed
CLI tree from setup. The CLI is rebuilt by lab setup but is not invoked by a
reference case; Z3's own executable remains identified by `referenceVersion`.
Capture hashes before solving, outside subprocess timing. Reference runs therefore
require the installed bench and CLI distributions, using the same provenance
manifest mechanism as Klause cases.

These are solver-reported reference verdicts (`referenceValidation`), separate
from independently checked source witnesses or refutation certificates. The lab
persists the solver/version rows through its ordinary reference backend; raw models
and diagnostics remain in case artifacts. Frozen baseline runs use `reference=any`,
a ten-second budget, one processor, no result-cache replay and explicit repetition.
Preserve every planned identity and unsuccessful outcome in external evidence.

Each returned subprocess record retains the actual child exit in `stats.exit`,
including a nonzero exit after a verdict. Lab command exits belong to the containing
harness process. Older records without this field leave the child exit unavailable;
a witness or proof marker does not establish a zero process exit.

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
records the lowering policy. See [input semantics](../docs/03-input-semantics.md).

Unsupported syntax, missing coordinates, residual constraints, compiler failure
or the ten-second external timeout leave validation unknown. Checking is outside
the solve budget; hashes must match and stay stable. Rejected/unchecked grid
candidates earn no solution credit. A checked grid witness proves feasibility,
not a source optimum/refutation. `reportedFeasible`, `reportedObjective` and
`reportedProven` retain the original claims. Integer opt-in also captures stdout
SHA-256 as `sourceOutputSha256`; unknown checking retains the reported witness.
Earlier incumbents are unchecked, so attribution is limited to the final objective.

SMT-LIB satisfaction runs opt in with `param=source-validation=true` under policy
`smt-external-original-claims-v2`. The CLI retains its original budget, seed and
engine. The bench invokes established external tools after the native solve;
there is no internal mathematical checker or custom fallback.

The adapter downloads hash-pinned upstream Dolmen 0.10 and Z3 4.15.3 releases
into the check's private directory when no executable is configured. It removes
those executables after checking and does not change host installations. Published
Dolmen binaries cover Linux x86-64 and macOS ARM64; the macOS asset named
`dolmen-macos-amd64` contains an ARM64 executable and requires the host's GMP library
at `/opt/homebrew/opt/gmp/lib/libgmp.10.dylib`. Other platforms need a configured
Dolmen executable. Z3 release selection covers Linux/macOS x86-64 and ARM64.

Set `KLAUSE_DOLMEN` to an absolute Dolmen executable path and
`KLAUSE_DOLMEN_SHA256` to its SHA-256 to override the downloaded release.
SAT checking passes the complete original
source unchanged and supplies `sat` followed by the exact final printed model on
stdin. Dolmen receives `--check-model=true --strict=false --report-style=minimal
--warn=-all --time=30s --size=4G`. Only a clean successful check accepts the model.
Algebraic syntax must be supported by the selected Dolmen build; `root-obj` is not
rewritten by the adapter. The [competition Dolmen build](https://github.com/SMT-COMP/dolmen)
uses external algebraic arithmetic, while upstream release coverage differs.
A build's existence does not establish compatibility with a particular saved model.

Set `KLAUSE_Z3` and `KLAUSE_Z3_SHA256` to override the independent native UNSAT comparison tool.
The Z3 CLI receives the complete unchanged source with `-smt2 -T:30 -memory:4096`.
One complete `unsat` answer can retain an already reported native refutation;
SAT, UNKNOWN, multiple answers and incomplete output cannot. This compares results
and does not check a native proof certificate. Independent checking never promotes
a native UNKNOWN or establishes an optimum.

Saved SMT claims use `validate-solution suite=<id> problem=<name> output=<stdout>
record=<producer.json> out=<evidence directory>`. Preserve the original producer
record, including its recorded validation policy. The replay receipt identifies the
current external policy separately. Optional `checker=<absolute executable>` and
`checker-sha256=<digest>` select a job-private Dolmen binary for SAT or Z3 for UNSAT;
otherwise the configured environment or pinned release supplies the tool. Replay checks the catalog
source hash and exact retained model/output without invoking the native solver.

Unavailable tools, unsupported input, timeout, interruption, changed bytes or
incomplete cleanup leave the claim unverified. Release downloads are bounded to
64 MiB and 30 seconds of observed elapsed time, with finite connection/read timeouts.
Extracted executables are bounded to 128 MiB. Executables are checked against their
hash before and after use; receipts retain release URLs and archive digests. Adjacent `smt-source-check-*` directories retain original
source/model/stdout, the producer record, the exact tool response input and a receipt
binding those bytes to the executable hash, command, true exit, timing and raw streams.
Linux enforces a 4 GiB address-space limit. macOS samples the live process tree's
resident bytes and stops checking after an observed 4 GiB excess. The system
`time -l` utility retains the waited child's kernel RSS high-water value separately
from checker output. Receipts bind its log hash, sampled peak, sample count, kernel
peak, limit, completeness and exceedance. Sampling is an observed process-tree guard;
well-formed zero-RSS rows are terminal observations only after every captured process
has exited, with one bounded process-reaper wait. They add no positive sample and
cannot replace the waited-child kernel high-water evidence. Missing live processes,
malformed rows and earlier failed observations continue to withhold acceptance.
The kernel high-water value covers the waited child rather than a continuous
aggregate tree measurement. An excessive peak, missing or malformed observation,
or incomplete cleanup withholds acceptance. Linux receipts identify address-space
control without claiming a measured RSS peak. Both hosts bound CPU and output file
size; the tools also enforce their own memory and time limits. The JVM allows 35 seconds
for the process and bounded cleanup; output streams are each limited to 1 MiB.
Checker time is separate from native solve and incumbent time. SAT checks issue no
solver query; incomplete reference output leaves the completed query count unknown.
Raw cache hits are checked afresh; use `-Dklause.bench.cache=false` for a fresh solve.

`bench.profile.SmtSourceReplay` delegates complete sets of saved SMT claims to
the same external adapters without running Klause. Supply `input64=<base64 zstd JSON>`
and `sha256=<compressed digest>`. Schema 1 contains `sources` metadata and `models`
with catalog `suite`/`problem`, exact `producerJson`/`stdout`, source/model hashes,
and preserved producer `bindings`. Each model uses the complete unchanged catalog
source and printed model. The replay records tool startup, per-model receipts and
a final complete/partial summary; non-SAT source markers stay outside Dolmen's
model-checking scope. Schema 2 uses `claims` with the same fields and a `scope`:
`ground-original-smt-witness` for SAT models or
`independent-original-smt-refutation` for already reported native UNSAT claims.
The latter supplies a null model hash and uses the pinned Z3 CLI. Its completed
reference-query count stays separate from native solver invocations; incomplete
answers remain unverified. Both tools retain memory receipts. Mac replay checks
the RSS guard with a below-baseline process allowance before checking claims.
Requests are limited to 128 KiB encoded/16 MiB expanded. The main stops starting
claims after two minutes; individual checks retain the ordinary 35-second process
limit. Replay supplies no new native decision, proof certificate, optimization or
performance credit.

### MPS independent point validation

MPS runs opt in with `param=source-validation=true` under policy
`mps-miplib2017-point-v1`. The bench invokes the established
[MIPLIB solchecker 1.0.4](https://miplib.zib.de/download.html), with its own original-MPS
reader and GMP rational evaluator. No solver query, repair, optimum check or
infeasibility certificate is performed. Only an accepted supplied point receives
feasibility and objective credit. Native proof claims remain uncredited; original
claims remain in the `reported*` fields.

The build downloads the official `miplib2017-testscript-v1.0.4.zip`, pinned to
SHA-256 `37b21a7f1ee968aed60675fb070c9286eccc6a953c8bc50120c9246b052b47b9`.
It extracts only the checker C++ sources, README, Makefile and MIT license, compiles
the unchanged upstream main with GMP and zlib, and packages those files and the
binary in the non-published bench artifact. The optional converter is not extracted.
Building requires a C++ compiler and GMP/zlib development packages. Invocation
supports Linux with GNU `timeout`/`prlimit` and macOS with the system shell limits.
Other hosts remain unverified. The packaged executable targets the build host;
rebuild the bench when changing OS or architecture.

The adapter inserts a neutral `NAME` header or an empty `RHS` section when the
original omits those optional headers required by MIPLIB. All original data
records remain verbatim; the receipt names inserted headers and binds both the
original `source.mps` and actual `checker.mps` bytes. No numerical values change.
The adapter translates the retained final stdout point into SOL variable/value
lines and `=obj=`. Duplicate or malformed coordinates are declined. MIPLIB's model
variable census must equal the number of supplied unique coordinates, and any
unexpected-variable diagnostic declines the result. Together these checks require
every original variable exactly once, including unconstrained variables. The
source sense, final witness, exact reported objective and full stdout must agree
with the producer record. A successful exit alone supplies no credit: complete
`Integrality`, `Constraints` and `Objective` results must all pass.

An exact pass uses explicit zero linear and integrality tolerances. A default run
also performs a pass with linear tolerance `1e-7` and integrality tolerance zero;
objective equality must pass the zero-tolerance run. The receipt distinguishes
`exactFeasible` and `toleranceAccepted`. This version adopts upstream MIPLIB
acceptance, which differs from the solver's source-tolerance policy. Upstream
1.0.4 scales a row side by
`max(1, positiveActivity, negativeActivity, abs(side))`, with the negative sum
retaining its sign; bounds use `max(1, abs(bound), abs(value))`. It uses finite
`1e20` sentinels for open sides. These checks can decline points accepted by the
solver; a declined point receives no validation credit. Equal numeric tolerances
do not establish equal acceptance policies.

The adapter screens known upstream reader limitations without evaluating rows.
Supported source records use one named vector per section without repeated row
entries, one objective row, consecutive column blocks, unique objective coefficients
and decimal tokens of at most 31 characters with exponents between -128 and 128.
RANGES, integer markers, bounds and indicators are delegated to MIPLIB. BV must be
its column's sole bound record. Lower bounds after an UP record, objective RHS
offsets, nonterminating rational coordinates,
rational source tokens, longer numbers, inline section data, multiple free rows,
unsupported sections and semantics overrides remain unverified. Terminating rational
coordinates convert exactly to supported decimal SOL tokens. No custom parser or
mathematical checker supplies a fallback.

Each check retains original source, complete stdout, producer JSON, SOL, request,
upstream license, process logs and receipt in an adjacent `mps-source-check-*`
directory. SHA-256 bindings cover all inputs; the receipt identifies the upstream
archive and actual executable bytes, command, PID, exit, stream hashes and cleanup.
The executable is removed after checking. Input/source changes or incomplete checks
withhold credit. The saved result retains producer-record JSON, source retrieval
identity and complete receipt JSON with its hash. Timing stays outside solver and
incumbent timing. The policy separates raw-solve cache entries; cached points are
checked afresh.

Each native invocation has a 30-second JVM wall deadline and disabled core dumps.
Linux uses a 4 GiB address-space limit, 30-second CPU backstop and 1 MiB per output
file; GNU timeout also bounds wall time if its JVM owner is lost. macOS adds 4 GiB
to the pre-exec shell virtual-size baseline and applies the result as an absolute
address-space limit, retaining both numbers in stdout.
It also uses a 30-second CPU limit and at most 1 MiB per file through the system
shell. The shell baseline includes Darwin shared mappings; it is not a native
post-exec resident-memory measurement. Owner loss leaves a CPU bound rather than
an independent wall bound. Receipts name the actual resource policy. The JVM has a
70-second preparation/checking allowance, terminates owned
descendants on cancellation and reaps its child within five seconds. Failed limit
setup cannot earn credit. Source input is limited to 64 MiB, producer records to
4 MiB and ordinary stdout to 8 MiB.

`param=source-validation-gzip=true` selects `mps-miplib2017-point-gzip-v1`.
The JVM retains losslessly compressed stdout, with an 8 MiB compressed and 64 MiB
decoded limit. SOL conversion reads the complete original producer stdout before
compression; the checker consumes SOL rather than decoding stdout. Receipt bindings
retain both transport and original stdout hashes. The flag requires explicit MPS
source validation and is removed before invoking the solver.

XCSP3 integer runs opt in with `param=source-validation=true`. The installed bench
includes the independent `org.xcsp:xcsp3-tools:2.5` `SolutionChecker`, also used by
the ACE distribution. It checks a copied original XML source against the final
complete named integer instantiation and its exact reported objective. Every
source variable must appear once, including unconstrained variables and expanded
array cells. Duplicate, unknown, missing, malformed and out-of-domain coordinates,
violated constraints and incorrect objectives are rejected. Compact variable/value
forms are outside this checking surface. Reified/soft constraints, multiple
objectives, unsupported checker callbacks, oversized integer coordinates and the
ten-second/512 MiB checker subprocess limit leave checking `unknown`. An arithmetic guard admits only audited callbacks before
the pinned checker runs.
It checks signed integer task endpoints, cumulative loads at task boundaries,
bin-packing loads, ordered offsets and knapsack products/totals with exact integers.
Direct sums require every assigned product and running total to fit a signed
integer, including constant and variable coefficients. The source parser expands
array lists, groups, slides and blocks for these checks. Expression sums admit
unweighted terms and constant or variable coefficients. Every expression node,
intermediate addition/product and weighted term must fit a signed long and agree
with the pinned evaluator; the exact sum must fit a signed integer. This guards
the checker's long multiplication before its exact accumulation, including
cancellation between large terms. Embedded expression parameters in group/slide
templates remain unknown when the pinned parser cannot expand them. Indexed vector elements admit only `rank="any"`,
constant or variable entries, and an in-range index whose exact offset fits a signed
integer. Matrix elements audit row and column offsets separately, check the selected row's bounds (including ragged rows),
and bound every source entry to the reference checker's integer range. For satisfaction sources containing only
matrix selections, ragged matrices use an exact source-cell equality check with a distinct checker identity;
the pinned reference loader assumes a rectangular constant matrix. Mixed constraint sets or objectives keep
the reference checker and can remain unknown. Ranked and unindexed elements remain unchecked.

Supported expression trees are evaluated with `BigInteger` and compared with the
pinned evaluator, including its normalized intension tree. Matching cancellation
results remain checkable; any disagreement or unsupported operation leaves checking
`unknown`. Supported expressions include integer arithmetic (except powers),
comparisons and Boolean operators. Hybrid extension tuples, set expressions, powers,
logical meta-constraints, flow, channel, circuit and indexed extrema
remain outside the audited surface. Direct variable objectives retain the pinned
checker's exact sum/product/minimum/maximum/distinct-value aggregates; expression
objectives require the same exact arithmetic agreement. Nonconstant objective
coefficients and expression integers above 4096 bits leave checking `unknown`.

XCSP records use `validationPolicy=xcsp3-source-witness-v3` and
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

`com.eignex.klause.bench.profile.XcspWitnessReplay` checks saved witnesses on a
remote profile without invoking the solver. Its single argument is a base64 gzip
JSON array containing `sourceRevision`, original `source`, `sourceSha256`, original
`witness`, `witnessSha256` and optional exact integer `objective`. Keep the encoded payload below 100 KiB for the lab shell; split larger collections
into separate jobs.
Hashes are verified before checking. It retains the original records, installed harness/runtime
hashes, checker command, process exit, timeout, stdout/stderr and each check outcome.
Each checker subprocess has one active processor, a 512 MiB heap and ten seconds.
The lab's executed revision and setup identity belong with these results;
`sourceRevision` describes the historical capture and is not the replay build.
Acceptance certifies source feasibility and objective values, not optimality or
infeasibility proofs.

## Comparing configurations

Run separate configurations over frozen membership, hashes, settings, proof
strength, budgets, seeds and resolved binaries:

```sh
./gradlew :klause-bench:solveCampaign --args="solve suite=core kind=cop engine=cp param=var-selector=vsids timeout=30000 label=vsids"
./gradlew :klause-bench:solveCampaign --args="solve suite=core kind=cop engine=cp param=var-selector=smallest-domain timeout=30000 label=domain"
```

`output/compare.sh [--incomplete] <dirA> <dirB>` (needs `jq` and `bc`) compares shared cases by
feasibility, proof and direction-aware objective quality, using time to break ties.
Complete mode prefers proved optima; incomplete mode ignores proof and splits
ties equally. Reports include contradictory outcomes. Time prefers witness time,
then valid `stats.solveTime` seconds, elapsed time, then budget; undecided runs
keep the budget penalty. Distinct labels compare rebuilt revisions.

Compare matching semantics and include preparation, replay, failed recovery,
certification and disposal in consumer cost. Warm up, alternate paired repetitions
and report spread. Kernel, pivot or allocation improvements alone do not establish
complete-solve gains. Inspect retained arm failures and quarantine separately;
independent witness checks do not by themselves verify optimality/refutation.

## LP capture and replay

These opt-in tools isolate numerical basis operations.
Capture records a bounded operation stream; verification checks its identities
and residuals; replay or paired comparisons measure that recorded work. A kernel
trace does not establish correctness or performance of an entire source solve.

Use the basis corpus for numerical regressions and dense comparisons for basis selection.
[LP architecture](../docs/17-linear-programming.md) owns implementation contracts; the
sections above cover full solve measurements.

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

Separate model-facing caller tables retain the first four captured Klause frames
after excluding `util`, `bench`, `lp.engine`, `lp.lattice`, `simplex` and `arithmetic` packages.
The existing operation-caller tables retain their first three frames. These are
bounded views of recorded stacks; absent ancestors remain unknown. Filtering does
not establish a unique owner or cause, and counts overlap the phase and self tables.
Both CPU counts and estimated allocation weights are summarized after CLI timing;
recorder settings and solver execution are unchanged.

The polynomial checkpoint caller table starts after a captured
`PolynomialArithmetic.step` frame and retains the next four Klause frames, including
arithmetic mechanisms. This exposes the operation polling a cancellation token when
the first captured frames belong to cancellation adapters. Missing checkpoints and
missing ancestors have separate unknown labels. Each CPU sample contributes once;
this table overlaps the other CPU views and does not reconstruct omitted frames.

Captured context-marker tables join each event's model-facing caller tuple with
observed concrete entry methods: `ResumableOpenTheory.runSlice` or
`OpenTheoryEngine.OpenTheoryRun.advance`, `RetainedLinearQueries` check/preparation,
`ExactNeighborhood.advance` or `OpenExactNeighborhood.check`, `LpProbeSolver.probe`,
and `QfLiraSolverKt.sharedExactLiraSource` or `PreparedSearchComponents.prepareSource`.
Nested JVM method frames retain their entry marker. Missing captured ancestors remain
unknown; multiple contexts remain explicit, including a common engine ancestor and a
retained-query or neighborhood entry together. These markers do not establish unique
invocation identity or public/private authority. Absence of a private marker does not
prove complete public traversal. Each event contributes once to each context table;
all context/caller groups are printed. Tables are overlapping facets of the same
samples and allocation weights, so their totals must not be added together.

`com.eignex.klause.bench.profile.CliRecordingSummary` replays retained recordings
on the remote lab host. Pass one positive `sourceJob=<id>` and one to 64
`recording=<case>:<measurementUUID>:<rawSHA256>` arguments. It derives sibling jobs
from the lab's `JOB_DIR`, reads only the selected fixed recording/metadata paths,
rejects symlink escapes and files over 16 MiB, and verifies recording identity
before and after parsing. Original measurement and available solve-record hashes
remain separate from the parser build/runtime in its output. Use a maintained
`lab profile` job with `host=lab`; it runs no CLI or solver and makes no timing or
verdict claim. Missing or trimmed source files fail explicitly. No inline recording
transport, public upload or local JFR parsing is needed.

```sh
./gradlew :klause-bench:bench --args="solve suite=mzn-bench name=mario engine=cp profile=cpu timeout=30000"
./gradlew :klause-bench:intDomainMicrobench
```

The Gradle hook accepts `-PasyncProfiler=/path/to/libasyncProfiler.so`,
`-PprofEvent=cpu|alloc|wall`, `-PprofFormat=traces=30`, `-PprofOut=<path>`.
Retain raw profiles/resource reports externally; compare uninstrumented paired
runs separately. Inlining/frame removal does not establish saved CPU time.
[LP capture and replay](#lp-capture-and-replay) covers numerical and owner lifecycles.

### Compressed profile captures

Internal profile entry points can wrap their body in `ProfileCaptureArchive.run`.
After success or failure, it packages `$OUT` into `capture.tar.gz` and records the
archive hash plus every original file's size and hash in `capture-manifest.json`.
It extracts and verifies the archive before removing the original staging files.
An action failure stays primary; an archive failure is retained as a suppressed
exception when both fail. Symbolic links are rejected before traversal, and an
existing archive refuses overwrite.

The live parent `profile.jfr` stays outside the archive so the lab wrapper can
finalize it. Packing runs after the profile body, outside solver timing. Consumers
must unpack and verify the manifest before source filtering or publication;
compression establishes byte preservation, not source feasibility or performance.

### Linear containing-stop attribution

`bench.profile.LinearStopAttributionProfile` accepts one `request64=<base64 JSON>`
argument containing up to eight `{suite, problem, sha256, seed, bounded?}` entries.
Run the committed profile on AWS with one processor. It preserves original source
bytes, installed distribution/runtime/harness provenance and actual JVM options.
The installed CLI frontend supplies its source admission, routing token and selected
request; the public pipeline executes that request with the CLI's retained deadline,
shared preparation allowance and default mixed engine. The CLI solve driver and
output protocol are not executed, so compare ordinary CLI streams separately.

Each default entry runs plain then observed under separate unchanged 60-second
containing deadlines. Observation retains the first existing true routing,
preparation and containing poll's stack, without extra delegate polls or charges.
These tokens have distinct scopes: a routing/preparation stop can leave the containing
solve active. A private numerical decline does not establish either stop.
Results retain the route, final reason, native counters and all captured stacks;
missing observations remain absent. Each captured stop records its stack-collection
nanoseconds separately from run time; delegation and JVM warmup overhead remain outside
that measurement.
Optional `bounded=true` runs plain/observed/observed/plain controls with eight
complete decisions or four heuristic proposals and 10,000 heuristic instructions,
using the complete or heuristic engine alone. These explicit diagnostic controls
check outcome and deterministic-work parity; they establish no default-route speedup,
source refutation, independent witness acceptance or optimization claim.

### Polynomial witness work checkpoints

`com.eignex.klause.bench.profile.PolynomialWitnessProbeProfile` diagnoses the bounded
root witness producer before CAD. Its single `request64=<base64>` argument encodes
up to eight `{id, base64, sha256}` original SMT sources. The decoded request is bounded
to 48 KiB and each unchanged source to 32 KiB. Run it through an AWS lab profile at
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
parsing and retains those bytes. Submit the committed diagnostic through the lab
without host or machine placement overrides.
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
Pipeline caller charges include source/search preparation and are separate from
the native arithmetic snapshot. This mode does not render a CLI model or independently
validate a source witness. Keep ordinary CLI records for publication/control evidence.

`pipeline=both` records the pipeline trace and the separate direct gate/query traces
for every source in the same profile invocation. Each producer retains its existing
containing deadline; the complete paired question must fit one whole profile allowance.
Use the captured dimensions, internal counters and terminal stacks to distinguish
representations and stopped operations. Paired diagnostic timings establish no
default-route performance gain, and direct results supply no shared-source acceptance.

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

### Boolean objective descent

`com.eignex.klause.bench.profile.BooleanObjectiveDescentProbe` compares the existing
CBLS feasible-descent and objective-bound repair modes from one retained WCNF witness.
It also compares native linear scoring with a functional Boolean objective view.
The input must retain explicit unary penalty folds. Supply `suite`, `problem`, the original source
`sha256`, a gzip/base64 original `v` bitstring in `witness64`, and counted `work`.
Optional `prepareMs=500` and per-run `timeout=5000` bound preparation and execution.
Run the committed main through an AWS lab profile at a trusted pinned revision.

Each variant gets a fresh worker, seed 3, the same prepared model and initial point.
The probe records exact source and search costs, final original witnesses, actual
moves, strict improvements beyond the supplied point, materialization and run times,
source bytes and build provenance. The initial point remains the retained best when
an arm finds no improvement; `nativeWitnesses` distinguishes returned arm witnesses.
Check `cases.json` with the independent original-source checker. Profile timings and
explicit mode comparisons are diagnostic; use ordinary matched whole-CLI runs for
default-route performance claims.

With `conditional=1..32`, the probe instead ranks unpinned unit source flips by
their objective delta and starts a fresh native CBLS satisfaction walk for each
selected target. It preserves the prepared model's effective root pins, holds the
target flipped, and reconciles its unary definitions. Each walk stops at feasibility,
the counted `work` or `timeout` limit, or a missing move. It uses the public state
and strategy kernel without engine restarts or further propagation. Rows report
remaining hard cost, pin preservation, setup and search times, and actual moves;
only feasible rows carry original witnesses. A repaired target may worsen other
objective terms, so strict improvements are measured against the original point.
This conditional diagnostic does not measure whole-engine or default behavior.

### Seeded mixed MPS completion

`com.eignex.klause.bench.profile.MixedMoveCompletionProbe` runs numerical local
search from a cold start or a source-checked supplied MPS point. Pass `input=<original MPS path>`,
`suite=<catalog suite> problem=<name>`, or `text64=<base64 original bytes>`, with
optional `initial64=<base64 JSON>` mapping every
original column name to an exact integer, decimal or rational string. Optional
`exact=false`, `instructions=20`, `seed=3`, `repeats=3` and `timeout=3000` retain
bounded deterministic controls. File and catalog inputs require `sourceSHA256=<original hash>`;
inline bytes pin their own identity. Execution uses one active JVM processor, forking a
child JVM with its own recording when necessary. The supplied point must meet the configured source guarantee: exact feasibility for
exact requests, or the existing original-source tolerance check in default mode;
no bounds or rows are added to it. A private objective auxiliary is initialized from
the supplied point's original source cost. The ordinary finite mixed projection and numerical
leaf completion own execution. An unavailable numerical completion remains undecided.

Run this committed profile through the lab client on AWS at an immutable revision.
It retains original bytes/hash, supplied point/hash, installed CLI/harness/runtime
identities, settings, every completion verdict/work charge and published named points.
Each attempt also records its finite-coordinate hash, residual LP verdict, collected
LP statistics and elapsed time; the first sixteen retain their complete finite point.
These private proposals carry no witness credit. Absence of LP observations means
the loaded adapter has no observer or did not enter its numerical path.
`lowering=cli` retains an exactly representable hybrid source in exact mode, matching
the CLI's initial lowering choice. The default `lowering=exact-source` preserves
the supplied-point diagnostic's explicit exact source rows. Neither mode runs CLI
routing or presolve; use ordinary CLI comparisons for integrated recovery claims.
`residualObjective=feasibility` asks the same numerical completion to find a point
without minimizing the residual objective; the search still measures the original
objective. The default is `source`. Optional `lambda=<nonnegative number>` selects
linear cost shaping and class weight normalization for the diagnostic; absence keeps
its standalone feasibility-first shaping. These controls are recorded independently
and do not change source witness checks or confer residual optimality.
`pointRecovery=true` requests the numerical engine's existing bounded sparse point
repair through its private solve context. Its default is false. It retains the
existing repair, arithmetic and containing-work limits and still requires exact
row and coordinate verification; a repaired point supplies no optimum proof.
`cases.json` retains supplied points and source hashes; `sources/` contains the
original bytes. Independent validation uses the established MIPLIB checker under
the [MPS input and receipt contract](#mps-independent-point-validation).
Returned points retain their exact-source or configured source-tolerance guarantee;
exact requests reject tolerance-only points. Original objective values are recomputed
from source numbers and returned coordinates, including tolerance witnesses. The
records export no optimum proof. Its elapsed time is an in-process
seeded diagnostic under JFR, not whole-CLI performance evidence. Keep raw files externally
and pair incumbent gains with repeated alternating unchanged-source CLI controls.

### Diffn candidate inspection

`com.eignex.klause.bench.profile.DiffnNeighborhoodProbe` loads a saved integer
MiniZinc witness into its compiled finite model and grades bounded structured
Diffn moves. Pass `suite`, `problem`, original `modelHash` and `dataHash`, the exact
integer `objective`, `draws=32` (1–256), and `values64`: a base64 canonical minified JSON object of
named integer/Boolean scalar or variable-array values. Supply all searched source
coordinates from an independently checked witness. Conflicting aliases, changed
sources, unsupported float coordinates and mismatched objectives decline. Root
pins and definitions are reconciled before scoring; a remaining nonzero complete
cost records an incomplete/invalid compiled assignment and offers no proposals.

Run it as an AWS lab profile at a trusted pinned revision. It preserves original
model/data, compiled FlatZinc, source-variable mappings, supplied values, compiler version and installed
build/runtime hashes. `proposals.json` records the initial objective and every
candidate's predicted and committed complete cost, exact integer objective and
move. Up to sixteen complete improving candidates retain their source assignments
and run the existing independent MiniZinc source checker with an exact integer
objective. Other candidates retain an explicit unchecked status. Each candidate
starts from the same assignment; restoration recomputes
payloads. Definition propagation and all model constraints participate in grading.

This inspects a prepared neighborhood, with no solver walk or runtime-parity claim.
Its model grades are separate from independent original-source checks; only a valid
source-check receipt supports candidate witness credit. This does not certify an
optimum or refutation. Keep raw results outside the repository and
pair the diagnostic with uninstrumented source-checked whole-CLI comparisons.

### Incremental move grading

`com.eignex.klause.bench.profile.IncrementalMoveProfile` compares supplied integer moves
and generated repairs with fresh whole-model grading. Its `sources` argument is a JSON
array of `{name, format, text, ints, bools, moves}` objects. Formats are `flatzinc`,
`smtlib` and `xcsp3`; assignments list every compiled coordinate, and each supplied move
is `{id, value}`. `seed` pins initialization. Real coordinates and out-of-domain values
are refused. The probe uses ordinary finite scoring without inferred definitions. The `product-aliases`
suite supplies MiniZinc square, fixed-power, result-alias and guarded-power source regressions;
use `param=source-validation=true` to check their complete original-source witnesses.

Run the committed main through the lab client at an immutable revision. It retains
source texts, hashes, installed-build/runtime/dependency identities and every predicted,
committed and fresh grade under `$OUT`. A failed comparison exits unsuccessfully after
saving its records. Agreement checks incremental state consistency; original-source
witness validation remains a separate obligation. This profile measures no solve
throughput and verifies no proof.

`PowerMoveSourceProfile` accepts the same `sources` and `seed` arguments for
FlatZinc inputs. It retains the incremental records, then checks each complete
coordinate assignment against the independent original FlatZinc predicate checker.
Zero complete cost must agree with an exact valid source witness; unsupported checks
fail the diagnostic. Signed powers, aliases, zero exponents and overflow use original
source numbers. `source-checks.json` keeps source hashes, coordinates, check scope and
agreement separately from native grading. Run it through the same AWS lab profile
client at a pinned revision. Alternatively, `suite=<catalog suite> problem=<name>`
freshly compiles a MiniZinc source and retains its original files, compiled FlatZinc,
compiler/build identity and parsed native Power factors without solving. It fails
when compilation retains no Power relation. `factorKind=Abs`, `RealAbs` or `GridAbs`
selects an absolute-value retention check; `exact=true` selects exact float parsing.
The `abs-relations` suite supplies integer magnitudes, aliases, Boolean scopes,
optional values and real forms for original MiniZinc compilation and source checks,
plus XCSP3 distance and Boolean-scope source fixtures.
These diagnostics measure no throughput
and export no proofs.

## Validation

`./gradlew :klause-bench:test` checks the harness. Explicit `solveCampaign` runs,
`:klause:basisTrace --args="verify"` and `--args="replay"` are opt-in fixture
checks; numerical declines and missing inputs keep their normal semantics.
Run source-verifier regressions with its pinned requirements. Benchmarks, captures
and timing probes do not run as part of ordinary repository checks.

`source-ownership` contains self-authored original MiniZinc mixed integer/real
product controls, including an unrelated finite distinctness constraint and an
excluded integer value. Run CP/LS with default and exact modes; the harness records
the original MiniZinc source check separately from compiled FlatZinc semantics.

## Design evidence

These short summaries support current design decisions. The contracts themselves
live in the [docs](../docs/README.md) chapters each summary links; raw evidence
stays outside the repository.

### Portfolio slice policy

Retain the finite schedule's base 5000, maximum 150000 and growth 1.5. The comparison
found workload-specific benefits from alternatives without a general replacement.
[Portfolio scheduling](../docs/12-portfolio.md#slice-accounting-and-coverage) and
[resources](../docs/06-sessions.md#resource-allowances) own the policy and work currencies.

#### Scope and controls

The comparison covered 13 inputs across MiniZinc, XCSP3, MPS, DIMACS, WCNF and OPB:
312 runs with two seeds and two repeats per setting. Settings were implicit/explicit
defaults, base 1000, maximum 30000, growth 2 and a flat 5000 schedule. Each run used
one processor, a 200000 decision/move allowance and a 30-second outer deadline.
Source/build identities and effective settings were checked. Equal nominal caps
did not imply equal completed work when preparation or deadlines stopped a run.

#### Slice decision evidence

The smaller base reduced work to proofs on `flugpl` and `hole9`, but produced no
witness on Cargo `challenge05_1s_954`, where defaults produced checked cost-11032
witnesses at the same ledger charge. Growth 2 found checked cost-6 witnesses on
`GenHyperTW_grid5`; scheduling/allocation inputs did not show a general advantage.
The flat and smaller-maximum schedules likewise gave no consistent benefit.

These tradeoffs support the retained defaults on this selection. They establish
neither a globally best schedule nor deterministic outcomes under elapsed limits.

#### Acceptance and limits

Independent original-source checks under the recorded policies accepted 229 final
witnesses and checked all
72 completed proof claims through source refutation or better-objective exclusion.
Witness acceptance and proof credit remain separate. A further 52 one-/four-processor
runs stayed within the aggregate 20000-unit cap while retaining unused worker quota;
that check was not a matched-work speed comparison.

[Review evidence](https://github.com/Eignex/klause-lab/releases/tag/active-evidence-resource-scopes-slice-policy)
records specifications, build identities and source checks for revision
`fdf42c72c1e4eda1d9e4d421fb84fd0cd53318c3`. Its snapshot excludes source input bytes,
installed binaries, profiles and original subprocess stdout. Conclusions are limited
to the recorded selection and runtime.

### Numerical kernel decisions

These summaries support current implementation choices on bounded workloads.
[LP architecture](../docs/17-linear-programming.md), [certification](../docs/17-linear-programming.md#lp-certification) and
[dependencies](../docs/18-exact-arithmetic.md#numerical-dependencies) own the contracts; measurement procedures live
in [LP capture and replay](#lp-capture-and-replay). Raw evidence stays outside the repository.

#### Dense basis selection

Retain the bounded dense-LU selection and windowed updates. Captured bases came
from MIPLIB2017 `markshare1`, `gr4x6`, `22433` and the committed basis corpus. Linux amd64/JDK 25
measurements used three fresh JVM forks with warm, paired complete factor builds,
including ownership and numerical guards.

On the populated six-row `markshare1` basis, windowed/full-buffer elapsed ratios
were 0.983 for retained and fresh owners; windowed/sparse ratios were 0.480 and
0.498. Independent products and operation replay checked both solve directions.
Windowed updates charged 125 fewer deterministic work units per build. Source
controls preserved outcomes, with 54 final witnesses independently checked.

Direct dense-build benefit was measured at order six; orders 2–5 remain inside the
bounded rule without that direct evidence. Larger captured bases favored sparse
construction, and the `22433` recording covers only its captured prefix. These
results justify neither a wider dense rule, a dependency-upgrade speedup nor a
whole-solver timing claim.

#### Rational normalization

Retain the [fraction factory](../klause/src/commonMain/kotlin/com/eignex/klause/simplex/exact/BigFraction.kt)
paths that skip redundant normalization when coprimality is established.
A [fixed-work comparison](https://github.com/Eignex/klause/actions/runs/38043606727)
covered six `nec-smt` inputs, three seeds and work allowances 3000/6000. All 108
pairs matched non-timing counters and outcomes; median paired duration reductions
ranged from 7.41% to 15.53% across inputs.

[Independent source checks](https://github.com/Eignex/klause/actions/runs/38042314816)
covered 104 matched MPS/SMT records. Claimed verdicts agreed with source constraints;
four rounded default-MPS points received separate source satisfiability checks.
Deadline-limited runs traversed different prefixes, so they establish neither
identical-work timing nor completed-solve gains.

#### Cancellation polling

Retain the [polling contract](../docs/17-linear-programming.md#resources-and-cancellation)
and dispatch frequency. A [fixed-work comparison](https://github.com/Eignex/klause/actions/runs/38043602143)
used three `nec-smt` inputs and five alternating pairs at seed 1/work 3000.
Counters and unknown outcomes matched; median duration reductions were 0.45%,
-0.57% and -0.20%, providing no useful dispatch-speedup evidence.

Overlapping CPU-profile frames do not measure additive cancellation cost.
Regression coverage protects live deadline rearming and external cancellation;
frame removal alone does not justify weaker polling.

### State ownership evidence

The bounded comparisons support retaining concrete buffer owners, separate indexed
vector roles and one cursor/cleanup owner per run. [LP architecture](../docs/17-linear-programming.md),
[search](../docs/08-search.md) and [local search](../docs/10-local-search.md) own the contracts.

The LP consumer probe checked 2,688 states
across matched workspace, basis, pricing and traversal comparisons. An independent
vertex oracle checked source bounds, exact values/objectives/bounds and owner closure.
Results and numerical work matched; warm median JVM thread allocations changed by
at most +0.54% per measured consumer phase.

The local-search probe checked
12,800 witnesses across three DIMACS fixtures, satisfaction/minimization and legacy
versus seven-instruction slices. Results, charged work and retained-state digests
matched; warm median thread allocations changed by at most +0.4%.

Independent source checks accepted 212 numeric/Boolean CLI records, including 60
exact MPS records. Thirty-two rounded default-MPS optima were excluded from exact
proof acceptance. Witness checks alone do not certify optimality or refutation.

These results establish neither whole-CLI speedups nor native allocation totals or
corpus-wide behavior. The sections above
own pinned builds, paired measurements and whole-CLI controls.

### Source-scoped derivation admission

Retain typed original-source derivations separately from native engine completion.
Evidence capture stays optional; unsupported capture withholds mathematical coverage.
[Source evidence](../docs/13-evidence.md) owns this contract, while
[portfolio admission](../docs/12-portfolio.md) owns native result transport.

#### Derivation decision evidence

The original-source controls at revision
`304a1fcbe374f1cd080bc64d626e77f3c931694f` retain original inputs, separate caller
conditions, actual claims, checker identities and runtime artifact hashes. Independent
standard-library rational checkers accept fourteen supported claims across quadratic
MIN/MAX, affine MPS and strict/mixed SMT sources. The maintained
[probe and checker workflow](#source-validation) owns these controls.

The controls include a decimal objective beyond binary64 precision, adjacent integer
branch coverage, Boolean branch coverage, a Farkas refutation, improving integer/real
rays and strict unattained limits. A conditioned witness retains Boolean true,
integer `18446744073709551616` and real `1/3` with matching original objective and
bound. A budget-exhausted result retains a checked global bound `1/2` without an
incumbent or terminal claim. These distinct outcomes require distinct mathematical
claims; a completion token cannot substitute for them.

The [platform validation](https://github.com/Eignex/klause/actions/runs/38468169304)
checks scope/claim matching, original-source feasibility, actual native publications,
optional capture, projection obligations, imports and cancellation. Missing coordinate
images, nonintegral integer images, nonpositive objective scales, foreign invocations
and incomplete branch coverage cannot acquire proof credit through metadata.

#### Acceptance limits

The independent checkers cover their declared affine and quadratic subsets. Native
admission, a checked witness and a checked terminal claim remain separate obligations.
A partial bound does not establish an optimum; a strict bound does not alone establish
an approach to a limit; an improving direction needs a feasible source base.

Capture does not export general proof files. Missing source premises, transformation
justifications, exact coordinates, retained inputs or checker reports withhold the
corresponding acceptance. New decoders and workers must consume these source scopes
and publication interfaces while preserving their existing resource and numerical
ownership boundaries.
