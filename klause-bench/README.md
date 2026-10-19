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
`cases.json` can be replayed by `SourceRecordCheck` against those original bytes.
Source replay installs the Python venv package on Linux; macOS lab hosts use their
existing `python3` venv support. Checker dependencies remain isolated in the job's
captured environment. The supplied neutral descriptor is a caller construction, not a certified MPS
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
pinned revisions. Use `SourceRecordCheck` with `captureSources=true` for independent
original-decimal validation; this fixture suite requires hash-named source bytes.
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
outputs, including `cases/<index>/record.json` for the offline source verifier.
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

`bench.profile.SearchSelectionProfile` compares default selection with all six
Boolean/finite/arithmetic category priorities on an original satisfaction SMT source.
Pass `source64=<base64 source>` and `finite=<bounded integer name>`; optional
`repeats=3 warmups=1` control alternating policy rounds. An independent finite
proposal splits that source column while exact linear arithmetic owns source checks.
The source must have a complete linear plan without CP-owned factors; shared
Boolean clause and cardinality participants retain their checks and propagation.
Each opening uses a ten-second token. Original bytes, decision traces, timing,
runtime/artifact hashes and raw exact models remain in `$OUT`. The existing pinned
SMT stream checker validates every complete witness outside the measured span.
These instrumented policy diagnostics provide no independent refutation proof and
stay separate from ordinary CLI cost comparisons.

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

`bench.profile.SourceAgreementProfile` compares one combined exact linear owner with
explicit registered linear peers over original real, wide-integer, strict and guarded
sources. Pass `repeats=N warmups=N cases=<comma-separated names>`; defaults are three
measured rounds and one warmup. The launcher starts a measured JVM with `-Xmx3g`,
Serial GC and one active processor, retaining its command and actual runtime options.
Modes alternate in each round in that JVM.
Each fresh invocation has a ten-second deadline and retains source/output hashes,
exact immutable witnesses, native work charges, attempted complete checks (including
a refused check), stop cause and runtime/library provenance under `$OUT`. SAT streams
are independently checked against the original SMT source after timing. The zero-check
control must remain unknown; conflicting source equations are analytic UNSAT controls
and do not receive an independent proof check. JFR and construction are included in
these diagnostic timings; they are not ordinary CLI throughput measurements.

`bench.profile.EncoderProviderProfile` runs caller-supplied factor-admitted SMT files
through normal source routing with default and private equivalent linear encoders.
Pass `inputs=<comma-separated repository-relative or absolute paths> repeats=N warmups=N seed=N`.
The profile uses `SmtLib.parse` and shared source setup. The `parser=admission`
and `parser=factor` options are aliases for that same source entry. Mechanism
probes require an original factor source, including representable bounded mixed products.
`entry=source engine=cp|ls` instead calls the public source-strategy satisfaction
facade with the same selected providers, containing token and seed. This mode rejects
objectives and records its actual native backend beside the requested entry and engine;
`entry=pipeline engine=cp` retains the direct pipeline comparison. Compare matched
revisions and check the recorded backend to distinguish finite and open controls.
`entry=source-optimize engine=cp|ls` calls the public affine source optimizer with
the original objective and direction. Both source facade entries accept `nodeLimit=N`
for one shared decision/move ledger. `binding=selected` supplies it through the native
strategy and common theory controls; `binding=common` supplies it through only the
common controls. Captures record the actual ledger limit and spend, open decisions
and native backend. Captures retain the native optimization verdict, minimized
exact incumbent value and interruption cause separately from the rendered SMT point.
The SMT replay checks points and objectives; global optimum, ray and refutation
claims require separate original-source checking. Optimization captures retain each
encoder verdict and a per-source comparison, including UNKNOWN differences caused
by admission or bounded work; verdict equality supplies no proof verification.
The finite in-process SMT adapter checks its finite factor-source precondition
before preparing lazily through shared setup. It disables optional bound-closing
deductions; other sources use the CLI adapter.
Source preparation declines remain explicit diagnostic UNKNOWN records with no
native execution work or optimization certificate. Source preparation refutations
are labelled separately from a native optimization result.
The original source bytes remain the checking authority. Constant nonzero integer
`div`/`mod` are checked with their original Euclidean SMT semantics; variable and zero
divisors decline independent checking. Each input also interrupts
a real selected installation through its caller token and records the resource decline;
inputs with no applicable installer record that control as inapplicable.
The private mode constrains scoped real auxiliaries with exact affine definitions and
retains conjunction/disjunction structure; guarded rows keep their selected built-in
encoding. Modes alternate in a measured JVM with one active processor, Serial GC and
`-Xmx3g`, under a fresh ten-second deadline per invocation. The profile retains original
source/output hashes, installation counts, charged native work, elapsed construction
and execution time, source revision and runtime/classpath hashes. Each SAT stream is
independently checked against the original source after measurement; UNSAT agreement
supplies no independent proof verification. Run the committed profile with `host=auto`
through the documented lab client and wait for capacity. These component/provider measurements include preparation
and differ from ordinary whole-CLI timings; pair them with matched source-checked CLI
controls before claiming end-to-end parity.

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
The `xcsp3-source-preserving-v2` policy uses the pinned packages in
[`xcsp3-cpsat/requirements.txt`](xcsp3-cpsat/requirements.txt) and the
`klause-xcsp3-cpsat:source-preserving-v2` image. Negative extension tables encode each forbidden tuple as a disjunction of
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

Build and inspect an owned reference image through the lab client on a pinned
revision, on the host providing Docker:

```sh
./deploy/lab profile com.eignex.klause.bench.profile.Xcsp3ReferenceImage <ref> "build XCSP3 reference image" lab 1200 build
```

Without `build`, that profile inspects the legacy `latest` image. It retains the
image identity, installed importer/parser bytes, dependency versions and installed
bench provenance. Importer behavior regressions run in the ordinary build gate;
they cover matrix axes, weighted expression sums, contradictions, unconstrained
source variables and objectives beyond binary64 precision.

`bench.profile.Xcsp3ReferenceReplay` accepts `request64=<base64 JSON array>` with
one `{suite,problem,sourceSha256,witness,objective}` entry per bounded job. It resolves
original XCSP sources through the catalog and requires their hashes to match.
The default diagnoses the legacy image; `image=current` diagnoses the maintained
image. It retains original bytes, parsed-witness violations, exact objective,
native model text, runtime identities and solver outcomes under ordinary, pinned
and isolated presolve settings. Each supplied witness also uses the maintained
independent source checker, retaining its accepted, invalid or declined result.
`mode=reduce` weakens the legacy native diagnostic
model while retaining a supplied witness, with at most 120 bounded solver calls;
its reduced models are diagnostic derivatives, never original-source reference
rows or independently checked proofs. Independently validate supplied and newly
returned witnesses against the original XML with the source checker.
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

`CliStreamCapture` with `checkSmt=true` checks complete satisfaction models against
all original SMT assertions. Optimization streams also require each model's exact
original objective and strict improvement in the declared direction. Satisfaction
checking supplies no objective or proof claim. Checker failures remain separate from
a successful solver exit; inspect the saved checker execution and output before
counting a source case as accepted.

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
admission and lifetime contracts live in [search](../docs/solver/search.md).

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
preparation can stop before its first check. See the [CLI statistics scopes](../docs/cli.md)
and [search contract](../docs/solver/search.md#statistics).

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

### Original corpus source capture

`com.eignex.klause.bench.profile.CorpusSourceCapture` exports original SMT archive
members without invoking a source parser, solver, checker or solve-record cache.
It supports the catalog's flat QF_LIA, QF_LRA, QF_NRA, QF_IDL, QF_RDL and QF_LIRA
archive suites. Submit a committed, pinned profile main on Linux AWS with one
`request64=<base64 UTF-8 JSON>` argument. The request is canonical compact JSON
with `schema: "corpus-original-source-request-v1"` and a nonempty `cases` array;
each case supplies exactly `suite`, `problem` and a nonnull lowercase 64-digit
`expectedSha256` of the original plain bytes. Keep requests and captured payloads
outside the repository. There is no default selection or reference-result filter.

The profile retains raw and resolved requests before transfer. Archive layout and
URL come from the same catalog declaration used by ordinary selection. The source
worker uses HTTPS without proxy inheritance, streams tar metadata through an owned
`zstd` decoder and copies only requested regular plain `.smt2` members. It does not
extract an archive into the corpus cache or change the cached collection. Missing,
compressed-inner, duplicate, unsupported and mismatched members remain failed;
no identity or source hash is invented. Leading `./` archive prefixes are normalized;
absolute and traversal names cannot become retained source paths.

Limits are 128 cases, 48 KiB of original request, 256 MiB per downloaded archive,
512 MiB total observed download, 8 GiB decoded archive bytes per collection,
200,000 members, 32 MiB per source and 128 MiB total source payload. Worker
receipts are bounded to 4 MiB each and 32 MiB in total. The Python worker retains a
1,200-second containing watchdog, 30-second network read timeout and 2 GiB address
space limit. The owning JVM bounds the worker to 1,230 seconds and each output
stream to 1 MiB. Child-group cleanup includes the decoder, including when its
Python parent has exited. Linux `setsid`, Python 3 and `zstd` are required; Python
runs isolated without provisioning mathematical packages. Cooperative IO and OS
cleanup can overrun a polling boundary; the containing owner kills the group and
withholds process completeness on timeout, overflow or interrupted draining.
Exited workers retain their pipes until the stream readers finish; group cleanup
still terminates owned descendants. An incomplete capture reports its exit,
stream completion and cleanup state in the failure message.

Under `$OUT/corpus-source-capture`, each completed payload receives an indexed
`.smt2` path only after source size/hash and complete decoder exit are checked.
Partial source and failed archive bytes are retained with transfer/failure metadata.
Failed HTTP reads retain bounded exception-partial bytes, account for all observed
bytes including omitted excess, and durably record the retained prefix hash and
primary transfer failure. Socket-buffer bytes not exposed by the read API remain
explicitly unavailable; transfer errors never trigger retries. Copy and receipt
IO preserve the primary failure and separately report durability/close failures.
Decoder kill, reap, stream retirement and signal restoration are independently
attempted; any unavailable cleanup or receipt prevents completion credit.
A successful archive download is removed after its digest and decoder receipts are
saved; full original source bytes remain. `capture.json` keeps every planned case
as captured, failed or not reached, and reports no solver/parser/checker calls.
`worker-process.json` retains true child exit, stream completion, primary failure,
checked group/helper/reap outcomes and stop flags. `worker-cleanup.json` joins final
registration release, hook removal and cleanup failures. Interrupted or failed
cleanup still attempts those durable receipts and withholds completeness;
installed-build provenance and worker hashes bind the producing code. Retrieve
outputs with maintained lab `files`/`cat` over authenticated HTTPS. Hash equality
establishes original-byte custody, never source satisfiability or a runtime gain.
The retained archive/source/decoder-stream/worker-receipt limits bound only the
profile's owned files. Harness JFR and other harness output are outside that
ownership. The lab's 1 GiB output retrieval limit can omit files; completion credit
requires the actual terminal inventory, `omitted.tsv` and collected payload hashes,
not only the worker's completion flag.

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
Check every first and final witness/objective with `SourceRecordCheck`, supplying
the retained hash-named sources and `witnessOnly=true`. Report prepared API evidence
separately from whole-CLI comparisons and exclude warmup rounds from measurements.

### CLI stream capture

`com.eignex.klause.bench.profile.CliStreamCapture` runs the installed CLI as a subprocess
and retains original input bytes, build/runtime provenance, command, stdout, stderr,
and a JSONL arrival record with byte offsets and subprocess liveness. Run it through
a maintained lab profile job. Capture settings precede `--`; the remaining arguments go
directly to the CLI, followed by the preserved input path. Compressed corpus inputs
are decoded before their bytes and source hash are preserved. Supply `input=<path>` or
`text64=<base64 input bytes>`, `extension=smt2|fzn|mps|opb|xml`, and optional
`timeoutMs=60000`. `marker=<whole-line regex>` counts completed output records;
`stopAfter=2` forcibly terminates the subprocess after the second matching line while
it is alive. Omitting `stopAfter` captures a normal completion. For example, SMT models
end with a line matching `\)`, and FlatZinc solutions end with `----------`.
To capture several cases on one provisioned instance, supply only
`batch64=<base64 JSON array of capture argument arrays>`. It preserves `requests.json`
and creates numbered directories for each case. A failed case records `failure.json`
and does not skip later planned cases.

For a self-contained catalog source, supply `suite=<id>`, `problem=<exact name>` and
`sha256=<original decoded source hash>` together instead of `input` or `text64`.
The source format selects the file extension, and a conflicting extension or hash
is rejected before launching the CLI. Catalog fetching occurs before subprocess timing.

`profileJvm=true` records the actual CLI child to `cli.jfr`, preserving inherited
`KLAUSE_CLI_OPTS` and the supplied CLI arguments. An already configured child recording
is rejected. The plan retains the child options and recording path; the capture retains
the child PID. After the child ends, the existing whole-CLI summarizer writes
`cli-profile-summary.txt`; a missing recording or failed summary writes
`profile-failure.json`. Profiled captures use the verified `capture.tar.gz` and
`capture-manifest.json` transport, including the original source, streams, recording
and summary. These recordings observe the supplied invocation without a warmup;
they do not add independent MPS witness validation.

`smoke-corpus/real-finite-domains-v1` supplies sparse disconnected sets, finite products
and aliases, original Boolean conditions, signed zeros and singleton membership.
The six satisfiable inputs support `checkFzn=true` original-source witness checks.
`empty-intersection.fzn` is a separate contradictory-source observation; absence of
a witness does not independently certify its refutation.

`planned.json` records the requested input and settings before launch. `capture.json`
records exit, marker count, interruption trigger, reader failure, and output hashes;
`arrivals.jsonl` preserves all observed lines, including terminal metadata. Marker counts
and subprocess liveness establish output timing. Independently check each retained
witness and exact objective against the preserved original input before treating it as
accepted arithmetic progress. This tool does not verify source semantics or proofs.

[verify_stream_source_results.py](tools/verify_stream_source_results.py) checks every
complete SMT model and its preceding exact cost against one original optimization
query, using the existing exact polynomial source checker. It requires strict
improvement in the original direction and rejects duplicate final publication.
Run `python3 klause-bench/tools/verify_stream_source_results.py input.smt2 stdout`
with the pinned [source-check requirements](tools/requirements-source-checks.txt).
For a hard interruption, add `--interrupted --minimum-witnesses 2`; incomplete trailing
output is retained as truncation, while a terminal optimization status is rejected.
These checks establish witness feasibility and objective values; they do not certify
terminal proof claims. Native numeric absolute-value terms are evaluated exactly
against each published point, including arbitrary-width integer magnitudes.
`checkSmt=true` runs this check after each captured SMT case on the same profile
instance. `minimumWitnesses=2` requires two complete checked models. The capture tool
creates an isolated Python environment using the pinned requirements, and retains
the checker commands, exits, stdout/stderr, timeout outcomes, Python identity, package
versions, and checker source hashes. `provisionChecker=true` additionally permits
installing `python3-venv` with `sudo apt-get` when the image lacks it. Checking and
provisioning happen outside the CLI solve budget. A failed setup is recorded once;
later cases still capture output and record their unavailable checker outcome.
`checkFzn=true` checks every complete FlatZinc publication with the existing independent
flattened-text checker and records its `valid`, `invalid`, or `unknown` outcome in
`source-check-fzn.json`. Satisfaction publications retain their source assertions
without an objective cost; optimization publications require exact cost consistency
and strict improvement in the original direction. An exact `_objective` coordinate
annotation supplies the cost when its display is rounded; the displayed helper must
agree with its binary64 approximation, and a separate objective comment must agree
exactly. It uses authoritative coordinate comments when supplied and
checks binary integer/float extrema, integer array extrema, and constant or variable
integer/Boolean/set array selection directly from the original predicates. Integer
`all_different_int` compares exact integral values, including values beyond binary64 precision.
`alldifferent_except_0` omits only exact zeroes before comparing distinct values.
Redundant and symmetry wrappers, including `fzn_` spellings, require their original
Boolean argument to hold; malformed arity or non-Boolean arguments leave validation
unknown. Boolean channels check exact zero/one values; conjunction/disjunction
folds retain repeated and empty arrays. Integer set membership and its reification
check original set values. Set values use normalized integer intervals for equality and source-universe bounds, without
enumerating ranges. Index bounds and repeated entries retain their source meaning.
It checks the preceding `% objective=` cost or finite `_objective` field against the
original solve objective. When both are present, their exact values must agree. Its source scope
is `flatzinc-binary64`, as described under source validation below. Interrupted
captures reject verdict markers and terminal `optimizationStatus` comments, including
unattained boundaries; these belong after invocation completion. Integer `int_div`
and `int_mod` checks use exact truncating arithmetic and reject zero divisors,
including word-boundary cases without wraparound.
`checkOpb=true` checks complete PB-competition `v` publications directly against
original OPB/WBO text using arbitrary-width integral arithmetic. Repeated and
complementary literals, conjunction products, hard comparisons, source minimization
terms and soft penalties/top retain their original units. The preceding final `o`
cost must match the checked model; missing coordinates or unsupported syntax do not
receive valid credit. Results are retained in `source-check-opb.json` with scope
`opb-integral`. This is a witness check; a terminal optimum or infeasibility marker
receives no proof credit. Interrupted captures cannot carry terminal status.
`com.eignex.klause.bench.profile.OpbStreamChecker` accepts the same saved-record
array described below, verifies original byte hashes and retains checking outcomes
without invoking the CLI.

`com.eignex.klause.bench.profile.SmtStreamReplay` checks saved SMT output through a
lab profile without invoking the CLI again. Its one argument is a base64 JSON array
of records containing `sourceRevision`, original `source` and `stdout` text, and
their `sourceSha256`/`stdoutSha256`; optional `interrupted` and `minimumWitnesses`
use the stream checker's controls. It verifies byte hashes before checking each
record, preserves the original solver revision separately from checker provenance,
and retains an outcome for every supplied record. Replay does not relabel the
original solve or establish its output timing.
For exact function sources, append `checker=functions` as the second profile argument.
That mode checks every completed original SMT publication through the existing rational
and symbolic function ground evaluator, including declaration sorts and function domains.
Unsupported algebraic carriers or symbolic identities remain unavailable. Optimization
metadata is rejected by this satisfaction witness checker; it supplies no refutation,
optimality or exported-proof credit and performs no solver search queries. Source,
output, checker and runtime hashes retain the same replay custody.
`com.eignex.klause.bench.profile.FznStreamChecker` accepts the same saved-record
array for FlatZinc output. It retains original bytes and source-check results,
including failures, without invoking the CLI. These replays check witness and
objective semantics; terminal proof claims require their separate evidence checks.
For larger captures, pass `gzip64=<base64 gzip bytes>` and `sha256=<expanded digest>`
instead of the single raw base64 argument. The compressed envelope preserves the exact
record bytes while keeping the launch command small. Its inline encoding is limited
to 96 KiB and expansion to 64 MiB, matching the saved-source bundle transport limits.

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

### Exact source probes

`com.eignex.klause.bench.profile.NraSourceCheck` checks actual saved SMT records through
`tools/verify_nra_source_results.py`. Pass `input=<base64 UTF-8 JSON array>`; each entry
retains `source`, its SHA-256 `hash`, `feasible`, actual `witness` and producer metadata.
The bundle checker rejects changed source bytes, ground-checks complete exact witnesses,
compares refutations with separate queries and leaves incomplete verdicts without claims.
Its `python` interpreter must have the pinned `tools/requirements-source-checks.txt`
dependencies. Checking outputs remain outside the repository; retain interpreter and
dependency identities alongside the original inputs, producer revisions and reports.
`preparePython=true` explicitly provisions the pinned checker environment on an owned
Ubuntu analysis instance; its installation and runtime identity log stays under `$OUT`.

`checker=transcendental` checks a saved bundle with `sources` and `results` arrays in
the `TranscendentalSourceProbe` schema. It uses the independent rational/symbolic
ground checker, requires original source hashes, and reports unsupported witnesses
separately. Its parser shares the pinned Z3 dependency; use `preparePython=true` on
an analysis instance without the checker environment. This mode checks no UNSAT proof.

These entry points can run as committed-code lab profiles with explicit AWS host,
one machine and one parallel command. Use the lab client with
`LAB_URL=https://lab.eignex.com` for submission, status, remote access and retrieval.
Build/test/lint gates remain in GitHub CI.

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

SMT-LIB satisfaction runs opt in with `param=source-validation=true` under policy
`smt-original-polynomial-claims-v3`. The ordinary CLI runs with its original budget,
seed and engine; the bench checks the returned claim afterward. Complete SAT models
use the maintained [polynomial source verifier](#polynomial-source-witness-verifier):
every declared Bool/Int/Real coordinate, including unused declarations, must be present
with its exact sort and value. Rational and `root-obj` algebraic coordinates must make
all original assertions and query assumptions evaluate exactly true. Ground checking
issues no solver or optimization search query and supplies no optimum or refutation
credit. Unsupported, incomplete, approximate or invalid models receive no accepted claim.

A reported UNSAT with a normal process exit and a native proof flag permits
independent checking of the full original source. For Bool/Real polynomial inputs,
an exact source derivation can establish a contradiction without a solver query.
Only necessary top-level conjuncts supply premises. Direct affine comparisons give
strict or closed coordinate bounds; positive sums of nonnegative even monomials
give closed bounds on their pure-square terms. Integer square-root ceilings and
Python rational arithmetic enclose those coordinates. Cartesian sign sets or
closed polynomial intervals must exclude an original required relation. Predicates
under disjunctions, implications and conditional branches supply no premises.

The bounded derivation recognizes sums of source monomials without distributing
additive factors, with powers through 64, rational components through 16,384 bits
and at most 32,768 monomials per predicate. Unsupported or larger forms decline
this optional check. The receipt retains original assertion indices, the contradiction
expression hash, its asserted relation and exact source bounds. `sourceValidationDerivationChecked` identifies
an accepted derivation. If none is established, one independent query receives all
original assertions and assumptions intact, with the existing 30-second limit.
Only a checked source contradiction or independent UNSAT result retains the native
refutation. Neither verifies an exported native certificate:
`sourceValidationProofChecked` stays false. UNKNOWN is never promoted by an independent
result. Optimization sources, failed producer exits and unsupported syntax remain unaccepted.

Validated Bool/Real polynomial inputs use Z3's `QF_NRA` solver factory. Signed integer
numerals remain exact polynomial coefficients. Integer declarations, nonliteral integer
operations and zero-power terms retain the general SMT factory.
The original assertions and assumptions are passed intact. Selection occurs once;
an UNKNOWN result triggers no second factory or query. `sourceValidationReferenceLogic`
and the bound child receipt identify the selected factory when a query is issued.
Bounded child stderr breadcrumbs distinguish source parsing, derivation and query
stages; they provide no acceptance or completed-query accounting.

The isolated worker selects a fixed-hash Z3 4.15.3.0 wheel for Linux x86-64/glibc
or macOS 13+ on arm64/x86-64. macOS requires Python 3.13+ for non-reaping child
observation. Bootstrap has 30 seconds and a 2-GiB memory budget.
Ground checking has 30 seconds/4 GiB, a 60-second supervisor and a 70-second JVM
allowance; refutation checking has a 40-second/8-GiB child, a 90-second supervisor
and a 100-second JVM allowance. Linux enforces the memory budget with `RLIMIT_AS`.
SMT supervision selects resident-memory accounting explicitly on macOS. It monitors the child's resident set through `proc_pidinfo` every 50 ms and
terminates an observed excess. It separately collects the kernel's per-child
`wait4` peak in bytes; acceptance requires the complete peak and every observed
sample to fit the budget, including short-lived peaks between samples. This is
resident-memory accounting, not a macOS address-space limit: shared mappings can
already exceed that limit before checker initialization. Existing bounded termination and drain waits remain additional ceilings;
the external ordinary-case deadline governs unfinished work. There is no larger retry.
Child termination is observed with `waitid(WNOWAIT)` before the process group is
closed. The waitable leader retains the group identity until the final reap;
stream callbacks and signal handlers cannot signal that owner after its release.
On macOS, an `EPERM` from group signalling is accepted only when non-reaping exit
observation and a complete bounded group-membership read identify the exited
leader as the group's sole member. Additional members or unavailable inspection
withhold cleanup credit.
Cleanup failures retain their diagnostics and available process logs in an incomplete
receipt, even when the child had returned a valid claim.
Exceptional exits retain bounded joins for both stream readers after process release,
including when release raises; an unfinished reader prevents another owned child.
Sources are limited to 64 MiB, models/stdout to 8 MiB each, producer records/receipts
to 4 MiB, each tool and raw child stream to 1 MiB. The native checker library is bound
to its private extracted wheel file and executable mapping; Python module/interpreter
providers retain the reviewed bounded identity checks. Linux reads executable
`/proc` mappings; macOS records dyld image addresses, Mach-O headers and CPU types,
binding the invoked executable, loaded interpreter, versioned Python framework or
dylib, and private Z3 library to their file identities. Framework launchers bind
the invoked wrapper and app-bundle interpreter to the same loaded versioned
framework; an unrelated executable or missing framework withholds acceptance. Foreign libraries, unsupported platforms or unavailable
resource custody withholds acceptance.

Adjacent `smt-source-check-*` evidence retains verbatim original source/model/stdout,
producer record, request, packaged tool hashes, private runtime/provider identities,
true process exits, all four bootstrap/check streams and cleanup receipts. Input and
source hashes are checked again before acceptance. A missing, mutated, interrupted,
overflowed or incomplete receipt withholds the claim. `checkedWitness` and the original
`finalWitness` remain separate; `reportedFeasible`, objectives and `reportedProven`
preserve the native report. `independentSourceRefutation` and
`sourceValidationSolverQueries` distinguish issued queries from ground and derivation checks.
An incomplete child leaves the observed query count `unknown`; the separate
`sourceValidationSolverQueryLimit` records the permitted count. The receipt marks
whether the query count was observed in a complete child result. Saved result statistics
retain the bounded verbatim native stdout as `sourceValidationProducerStdout`, with
its producer record and hash, so record-only collectors preserve those bytes.
Checker setup/check timing stays separate from native solve and incumbent timings.
Raw cache hits are checked afresh; use `-Dklause.bench.cache=false` for a fresh solve.

MPS runs opt in with `param=source-validation=true` under policy
`mps-original-rational-ground-witness-v2`. The installed bench uses the maintained independent
Python MPS parser in `verify_source_results.py`, shared with its symbolic proof
builder. The `python-fractions-mps-ground-v1` checker evaluates the supplied point
with exact `Fraction` arithmetic.
It checks the literal final stdout point against every original row, bound,
indicator and integrality declaration, and recomputes the original objective and
sense. Parsing and direct evaluation each run once, retaining every separate source
term for tolerance magnitudes. Ground evaluation constructs no symbolic model or
substitution graph. Default runs permit original MPS absolute/relative tolerances of `1e-7`;
`exact=true` requires zero slack. Tolerance magnitudes include cancellation between
separate original terms. Text bounds at or beyond magnitude `1e20` are open
for columns and each row side, including derived range endpoints; affine objective
constants remain literal. Original-file declaration defaults apply; unsupported
semantics overrides, syntax and incomplete points leave checking unknown.

Each check writes original source bytes, stdout, final witness and the complete
producer record under an adjacent `mps-source-check-*` directory, with SHA-256
bindings in `result.json`. The saved solve record also retains exact producer-record
JSON, source retrieval identity and the complete receipt JSON in
`sourceValidationProducerRecordJson`, `sourceValidationSourceIdentityJson` and
`sourceValidationReceiptJson`. Literal final coordinates/objective and source/stdout
hashes remain in the ordinary record. This preserves checker custody when ordinary
workers return only that record; preserve hash-matching original sources separately
for later inspection. Packaged checker/requirements hashes, interpreter/provider identities,
process logs and cleanup receipts accompany it. Valid/invalid receipts require successful
bounded bootstrap/check processes, complete stream bytes/hashes and matching child JSON;
missing or mutated mandatory custody leaves checking unknown.
The standard-library runtime supports CPython on Linux x86-64/AArch64 and macOS
x86-64/Arm64. Bootstrap and checking independently bind the actual `Py_GetVersion`
address through `dladdr`, hash its executable image and the interpreter executable,
and retain the actual `ctypes` and `_ctypes` module identities. Both process receipts
must agree on those identities. Interpreter/provider files retain their 64 MiB per-file
and 192 MiB total bounds; module files retain their 4 MiB cap.
The arithmetic receipt identifies mandatory file-backed `fractions` and `numbers`
module bytes. File-backed `math` retains its actual module origin and file identity;
built-in `math` requires its actual `built-in` origin, `BuiltinImporter` loader and
bounded Linux interpreter/provider mapping ledger. Unsupported origins remain unknown.
The ground import mode of `verify_source_results.py` loads no symbolic solver; its
original `Fraction` point evaluator remains the same evaluator used by source checks.
Symbolic proof tooling and the independent CI oracle retain the pinned Z3 dependency.
The ordinary ground runtime downloads no wheel and performs no global installation.
Setup is
bounded to 30 seconds/2 GiB, point checking to 30 seconds/4 GiB, and the worker to
60 seconds. The JVM reserves a 70-second allowance across preparation and waiting,
plus at most two five-second termination/reaping waits. With the solver's existing
40-second watchdog and two-second stderr drain for a feasible ten-second run, this
leaves 18 seconds for source/receipt I/O inside the ordinary 140-second case deadline.
The external deadline still governs incomplete work. Sources over 64 MiB, solver stdout over 8 MiB,
producer records/receipts over 4 MiB and checker streams over 1 MiB remain unchecked.
Private runtime directories are removed after checking. Missing dependencies,
timeout, incomplete output, changed custody or failed cleanup cannot yield valid
credit.
Linux child processes use absolute kernel address-space limits. On macOS the
kernel limit adds the same 2 GiB or 4 GiB allowance to the recorded pre-exec VM
baseline, which includes Darwin's shared mappings. The receipt retains the worker
PID, baseline virtual/resident bytes and actual absolute kernel limit. Each child
verifies its PID, phase and inherited soft/hard limits, reapplies that limit after
exec and checks it before processing inputs. Missing, contradictory or unavailable memory control leaves checking
unknown. The private MPS adapter retains the shared process-group, stream and
deadline owner without changing the SMT runner.
The solve record retains at most 64 KiB of supervisor output as base64, its prefix
hash, full file byte count and completeness flag so startup failures remain
diagnosable when only the record is returned. This diagnostic supplies no validation
credit.

For long incumbent histories, also pass `param=source-validation-gzip=true` to
select `mps-original-rational-ground-gzip-witness-v1`. Both flags belong to the
bench and are removed before invoking the Klause CLI. This policy accepts only
Klause MPS runs with explicit source validation and separates cache entries.
The legacy policy retains its 8 MiB raw-stdout cap. The gzip policy losslessly
compresses the complete stdout, admitting at most 8 MiB compressed and 64 MiB
decoded bytes, with an 8 MiB limit per physical line and retained checking envelope.
Compression polls the same 70-second JVM allowance; setup, ground checking and
outer case deadlines retain the limits above. Exceeding any bound leaves checking
unknown rather than selecting a shorter successful history.

The child consumes every gzip member to EOF, checks its CRC, decoded byte count
and full-stream SHA-256 against the producer, and retains every source-semantics
declaration plus the literal final witness and objective lines. The unchanged
ground evaluator checks this envelope against all original source obligations.
Malformed, truncated or mismatched streams cannot earn credit. `stdoutTransport`
binds compressed, decoded and envelope byte counts/hashes and complete decoding
into the matching child and supervisor receipts. Decoder custody binds the packaged Python RFC 1951/1952 implementation to the
same interpreter receipt. It checks stored, fixed-Huffman and dynamic-Huffman blocks,
back-reference distances, every member CRC and size, optional header CRCs, complete
EOF and all existing transport bounds. It uses no native compression or CRC fallback.
The decoder file hash must match the packaged tool ledger. Legacy native-zlib receipts
remain subject to their explicit module and Linux provider-ledger validation.
These are independent
transport obligations and do not add search or proof credit. Older failed records
are not reclassified by selecting this policy for fresh runs.

MPS ground checking performs no satisfiability search, repairs, replacement-point
construction, optimum query or refutation check. Only a valid witness retains
feasibility/objective credit; unknown/invalid checks and reported UNSAT claims
become undecided. All proof claims remain uncredited. Original claims stay in
`reportedFeasible`, `reportedObjective`, `reportedExactObjective` and
`reportedProven`, and `checkedWitness` is separate from `finalWitness`.
`sourceValidationSetupMs` and `sourceValidationCheckMs` are outside the solver
budget and elapsed/incumbent times. `sourceValidationReceiptSha256` binds the
retained receipt JSON to the saved result. The validation policy separates raw solve
cache entries; a raw cache hit still checks its retained point afresh and does not
reuse acceptance. The offline verifier's proof path is unchanged.

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

### Offline source verifier

[verify_source_results.py](tools/verify_source_results.py) checks `mps-core`,
`smtlib-core`, `miplib3`, `miplib2003`, `miplib2017` and the SMT corpus suites `smtlib-qfidl`, `smtlib-qflia`,
`smtlib-qflira`, `smtlib-qflra` and `smtlib-qfrdl` against original sources using exact arithmetic.
Vendored inputs are retrieved at the recorded revision; original MIPLIB3 inputs
are retrieved separately. All source hashes must match. `--source-root` instead
reads original uncompressed inputs named `<sourceHashes.model>.txt` from a supplied
directory, allowing the same checks without network retrieval. MIPLIB 2003, 2010,
2017 and OR-Library MPS records require this hash-matched source directory and use the same exact
row, integrality, objective and source-tolerance checks.
The five SMT corpus suites require this directory and their hash-matched original
bytes; they do not fetch a source by corpus path. Other suites are declined.

```sh
python3 -m pip install -r klause-bench/tools/requirements-source-checks.txt
python3 klause-bench/tools/verify_source_results.py /path/to/cases.json \
  --source-root /path/to/original-sources --records-root /path/to/raw-job \
  --output /path/to/source-checks.json
```

`bench.profile.SourceRecordCheck` runs this verifier on saved records through a
lab analysis profile without rerunning the solver. Pass `bundle=<https URL>` and
`sha256=<compressed SHA-256>` for a gzip JSON object containing `cases` (the case
array) and `sources` (original source SHA-256 keys with base64 original bytes).
For small saved-record bundles, `input64=<base64 gzip bytes>` replaces `bundle`;
the encoded input is capped at 96 KiB and the compressed `sha256` remains required.
Each `sources` value is base64 original bytes or an object with `gzip64` holding
base64 gzip bytes. Decompression preserves the original source hash and caps the
combined uncompressed sources at 64 MiB.
`rootBounds=true` additionally checks each recorded `lpRootBound` against the
unchanged original MPS constraints and objective, in minimized source units.
MPS statistics retain the positive integer `lpRootBoundScale` used by lowering.
The checker reads the exact binary64 bound from its round-trip decimal, divides
by that scale exactly, retains integrality, and requires the strict better-objective query to be unsatisfiable.
Missing bounds or source scales stay unavailable; nonfinite bounds, counterexamples and unknown
queries fail. This independent source query verifies no native bound certificate
and establishes no witness or attainment. Use records with unrounded root-bound
statistics; older four-decimal output can overstate the bound. The standalone
verifier exposes the same opt-in check as `--root-bounds`.
With `catalog=true`, missing sources are resolved through the maintained corpus
catalog on the analysis host and must match each record's original model hash.
This permits record-only bundles for fetched collections without redistributing
their inputs. Resolved suite/name identities are retained in `catalog-sources.json`.
It first creates a private environment with `python3 -m venv`. If creation fails,
it attempts the Linux `python3-venv` bootstrap through `sudo apt-get` and retries.
It checks bundle/source identities, provisions the pinned checker dependencies,
and retains checker logs, versions and a checked prefix on failure under `$OUT`.
Original unknown records retain their no-claim outcome; reference agreement does
not independently verify an exported proof.
For a separate point check, `witnessOnly=true` forwards `--witness-only` to the
verifier. Every record must contain a reported feasible witness. The report preserves
`reportedProven`, marks `verificationScope=witness-only` and
`proofCertificateChecked=false`, and omits optimality checks. The default still
requires independent optimality and infeasibility queries.

Inputs can be JSON, gzip or xz; `--records-root` selects `cases/<index>/record.json`
instead of embedded records. SMT models must cover source sorts and satisfy all
original constraints. MPS points must cover every column with integral integer
coordinates; missing, duplicate, unknown and nonfinite coordinates fail checking.
For a complete SMT witness, the verifier pins every declared source variable and
requires SAT under the original constraints; this also establishes source feasibility.
Complete exact MPS witnesses establish source feasibility by direct row evaluation;
absent or inexact witnesses retain the unpinned source query. Optimality and
infeasibility still require their independent source queries.
Witness pins enter an incremental scope only when later optimality queries need
the original unpinned constraints.
SMT records without a witness retain an unpinned source feasibility query. Query
failures identify the problem, expected and returned verdicts, and any UNKNOWN reason.
`--progress /path/to/checked-prefix.jsonl` flushes each completed record, including
cache matches. A failed batch retains this checked prefix without claiming the remaining
records passed; the final JSON output is written only after the whole batch succeeds.
Each solver query has a 120-second allowance; source parsing and witness construction
are outside that query allowance and require an enclosing job budget.
Exact MPS queries use Z3's finite-domain solver when every original integer
column has normalized zero/one bounds and nonzero objective weights are uniform.
Unequal objective weights retain the original arithmetic query. An exact pseudo-Boolean representation
preserves each source predicate and objective comparison, clearing rational
coefficient denominators and handling strict thresholds on the integer lattice.
Unsupported expressions or weights beyond the pseudo-Boolean API width keep the
general solver, as do mixed, continuous and wider integer sources. Reports retain
this query choice; UNKNOWN still fails checking.
The recorded command's `--exact` flag requires exact source feasibility; its absence
selects default MPS checking even when the arm name ends in `-exact`. Legacy records
without a command use that arm suffix. Default MPS `toleranceAccepted`
is separate from exact feasibility, using [scaled source tolerance](../docs/formats/mps.md).
Original rational coefficients/printed values avoid binary64 row overflow.

MPS objectives retain source coefficients, sense and RHS offsets. Default exact
objective text must match either the printed decimal point or its shortest-decimal
binary64 coordinates; numeric fallback permits only binary64 rounding. Exact-mode
points/objectives require exact equality. Rounded default points can be nonexact
while the original source remains feasible.

Claimed SAT/UNSAT require matching exact source verdicts. A proven finite optimum
needs a feasible source objective and exclusion of every strictly better objective.
An exactly checked complete MPS point with that exact objective establishes attainment
without rediscovering it through a satisfiability search. Strict improvement exclusion
still requires its independent source query.
Tolerance acceptance cannot justify either optimum or infeasibility. Unknown
records make no proof claim. Unbounded claims and unsupported constructs decline.
Records marked `stats.sourcePolicy=captured-linear-source` carry accepted-source
claims and require case-level `sourceTolerance` with `absolute` and `relative`
rational text from the captured request. Their MPS infeasibility and strict-improvement
queries use the same configured tolerance-accepted constraints as point checking;
exact requests require zero metadata and use the nominal source. Reports identify
this scope and policy in `sourceQuerySemantics` and `sourceTolerance`, and
cached nominal checks cannot supply an accepted-source proof. Other MPS records
retain their nominal-source proof scope. These checks establish individual source
claims, without verifying exported native certificates or general completeness.
An explicit default-MPS `tolerance-optimum` retains its source point, objective
and scaled-tolerance checks separately. It receives no exact-source optimality
credit; native tolerance-certificate verification is unavailable from saved records.
This marker cannot weaken exact-mode or SMT checking.
MPS `ENDATA` ends the source model; later records, including appended branching
priorities, do not contribute constraints, bounds or objectives. Unsupported MPS
sections before `ENDATA`, unsupported bounds, distinct RHS/range/bound vectors and SMT
function/sort declarations are rejected rather than guessed. The verifier is
independent of paired timing analysis and Klause's internal certificates.

[verify_boolean_source_results.py](tools/verify_boolean_source_results.py) checks
`DIMACS`, `WCNF` and polynomial `OPB`/WBO records against original source bytes in that same
hash-named directory. It accepts complete signed-literal assignments, OPB variable
names and WCNF bitstrings. Clauses, hard weights, Boolean integrality and integer
objectives use exact arithmetic. WBO checks evaluate weighted soft-relation
violations and enforce the strict soft-top ceiling on that violation cost. UTF-8
comments preserve original byte hashes.
OPB products use Boolean conjunction, including complemented and repeated literals,
in constraints, objectives and weighted soft relations. OPB bodies can omit the
variable-count comment; their range is inferred from every product literal.
Malformed terms and unsupported syntax are declined.

```sh
python3 klause-bench/tools/verify_boolean_source_results.py /path/to/cases.json \
  --source-root /path/to/original-sources --records-root /path/to/raw-job \
  --output /path/to/boolean-source-checks.json
```

The checker independently excludes better source objectives or refutes source
constraints using the pinned Z3 dependency with a ten-second limit. A recognized
pigeonhole subformula can establish a refutation directly. Timeout leaves proof
checking unknown; a feasible point receives no optimum credit without that check.
The exact `coreGuidedLowerBound` statistic is checked by excluding source objectives
below that floor, including records without an incumbent. `checkedLowerBound` records
only a completed independent check; an undecided floor uses `boundCheck=unknown`.
Floor telemetry and floor checking do not establish an optimum without a matching witness.
Errors are recorded per case and make the command exit unsuccessfully. The checker
supports JSON, gzip and xz inputs and preserves cases with no claimed verdict.
Pass `checker=boolean` to the saved-record `SourceRecordCheck` lab profile to run
this checker against the same hash-pinned `cases`/`sources` bundle. Its retained
source checks, runtime/package versions, checker hashes and process log distinguish
verified witnesses/objectives, independently checked proofs and unknown proof queries.
With `captureSources=true`, omitted source payloads are resolved through the current
corpus catalog and retained only when their original-byte hashes match the saved
records. The source capture records resolved identities and licenses. This option
performs source IO and independent checking without rerunning the solver.

### Exact function source controls

`com.eignex.klause.bench.profile.TranscendentalSourceProbe` runs original SMT-LIB
function sources through the shared satisfaction dispatcher. Its `sources` argument
is a JSON array of `{name, text}` objects. `controls` lists `ENGINE:cores` selections;
`seeds`, `repeats`, `work`, `timeout` and optional `cancel-work` pin the repeated
controls. Alternating control order reduces order effects. Run the committed main
through the lab client on a pinned revision; it writes `sources.json`,
`results.json`, payload hashes and runtime settings to `$OUT`. Retain the job's
revision/build/dependency hashes with those files. JFR profile times are diagnostic
costs, not ordinary benchmark throughput.

Check saved points against their captured source text with
`tools/verify_transcendental_source_results.py <sources.json> <results.json>`.
The independent ground checker uses exact rational arithmetic and explicit symbolic
exp/log and pi identities, validates integer/Boolean declarations and strict source
assertions, and rejects undefined log terms even under Boolean negation/disjunction.
Conditionals check only the selected branch. Algebraic-root carriers and unsupported
symbolic comparisons report unavailable; they are never rounded or accepted by
residual. This checker does not verify refutations, optimization claims or exported
proofs. Preserve unknown/decline records and check cancellation charges and portfolio
arm segments separately from mathematical witness acceptance.

The exact ground function checker retains lexical helper closures and parallel
`let` bindings. Function `distinct` checks exact operand values and repeated roles;
uncertified symbolic comparisons remain unavailable.

### Ground witness profile

`com.eignex.klause.bench.profile.SmtWitnessValidation` evaluates retained complete
named Int/Real/Bool polynomial SMT models against their original source bytes.
Submit a trusted committed profile main through the configured lab client, with
`machines: 1`, `parallel: 1` and an outer timeout of at least 1,200 seconds. The
single `request64=<base64>` argument encodes canonical compact UTF-8 JSON. Serialize
this shape compactly, without whitespace or duplicate keys, before base64 encoding:

```json
{
  "schema": "smt-ground-witness-request-v1",
  "cases": [{
    "id": "input-0",
    "source": {"bytes": N, "sha256": "<hash>", "base64": "<original bytes>"},
    "model": {"bytes": M, "sha256": "<hash>", "base64": "<complete model bytes>"}
  }]
}
```

Replace `N` and `M` with integer byte counts. Requests allow at most 16 distinct
case IDs and 48 KiB for the complete encoded request. Raw source and model payloads
are bounded to 32 KiB. A payload may add `"encoding":"gzip"` and encode gzip bytes
in `base64`, while `bytes` and `sha256` describe its original uncompressed bytes.
A gzip source is bounded to 4 MiB after inflation; a gzip model is bounded to 32 KiB.
Payload base64 is capped at 48 KiB. Validation never drops coordinates or rewrites
a source/model to fit these bounds. Inflation stops at the declared size
plus one byte. Unknown encodings, noncanonical base64, truncated or corrupt gzip
streams and original byte/hash mismatches are refused before child execution.
No catalog scan, solver call or reference search
runs. The main retains the verbatim inputs and packaged checker identities under
`$OUT/smt-witness-validation`, then provisions the SHA-256-pinned Z3 4.15.3.0
Python wheel in a private Linux x86-64 runtime without global installation.

Each ground child calls only the maintained `check_witness` function, with a
30-second watchdog and 16 GiB address-space limit. The containing supervisor has
a 1,100-second deadline; setup, raw streams, true process exits, stopped/overflow
coverage and cleanup are retained. `summary.json` distinguishes accepted, rejected,
unavailable and not-reached cases. A failed batch preserves its accepted prefix;
missing, unsupported or interrupted models receive no accepted source claim.
JFR is part of profile execution, so validation timing is not ordinary solve timing.
Ground acceptance establishes only the supplied original-source model, not an UNSAT
proof, optimization claim, reference latency or a current solver performance gap.

### Polynomial source witness verifier

[verify_nra_source_results.py](tools/verify_nra_source_results.py) independently
checks complete named Int/Real/Bool models against an original polynomial SMT source,
including native numeric absolute-value terms.
It reconstructs rational coordinates and `root-obj` algebraic coordinates using the
pinned Z3 dependency, pins every source variable, and requires every
original assertion and Boolean query assumption to evaluate exactly to true.
Source serialization quotes identifiers beginning with a minus sign and digit,
preserving their symbol identity for dependency parsing. Quoted numeral symbols
remain distinct from numeral literals. Original source bytes retain their recorded
hash; model-coordinate numeral decoding uses its separate scalar grammar.
Constant positive integer powers are serialized as shared multiplication expressions,
preserving integer helper result sorts. Fresh lexical bindings avoid source names;
zero powers retain the dependency's partial-power interpretation. Original source
hashes remain unchanged.
Algebraic coordinates use integer defining polynomials and one-based distinct
ordered real-root indices. Approximate decimal coordinates do not establish exact
irrational equalities. Missing, duplicate, unknown or sort-mismatched coordinates
are rejected. Integer coordinates must be integral and retain arbitrary precision.
Other nonpolynomial, optimization and incremental sources decline.

The ground witness route permits one auxiliary `/0` model definition with two
Real arguments and a Real result when that name is not a source coordinate.
Source admission requires every division denominator to be a nonzero rational
constant, so the division completion cannot affect any checked assertion. Its
body is not installed in the checking model. All original coordinates remain
required; duplicate or other auxiliary definitions are rejected. Scalar model
reading without that source admission remains strict.

```sh
python3 klause-bench/tools/verify_nra_source_results.py original.smt2 --witness model.smt2
python3 klause-bench/tools/verify_nra_source_results.py original.smt2 --expected unsat
```

`--witness` uses exact ground algebraic evaluation without a solver search query.
All declared Int, Real and Bool coordinates, including unused ones, must occur in
the supplied model. The checker registers those exact values once in an explicit
model and evaluates every original assertion with model completion disabled.
It rejects a false or unevaluated assertion at its original index; it does not
invent missing coordinates. Source S-expressions are rendered iteratively to
preserve their tokens and spacing at deep nesting. Assertions containing a zero
power retain exact substitution and simplification, so undefined `0^0` cannot
receive an arbitrary model value.
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

Compare matching semantics and include preparation, replay, failed recovery,
certification and disposal in consumer cost. Warm up, alternate paired repetitions
and report spread. Kernel, pivot or allocation improvements alone do not establish
complete-solve gains. Inspect retained arm failures and quarantine separately;
independent witness checks do not by themselves verify optimality/refutation.

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
on the remote lab host. Pass one positive `sourceJob=<id>` and one to eight
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
[LP capture/replay](../docs/lp/replay.md) covers numerical and owner lifecycles.

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
parsing and retains those bytes. Run the committed profile through the lab on AWS.
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
statistics, caller charges and the first containing deadline stack. Its single
deadline, set by `timeoutMs` or the 60-second default, covers admission, preparation
and execution. It records a
pipeline trace instead of direct-query checkpoints or a separate gate inspection.
The source must select polynomial admission; a linear route fails explicitly.
Pipeline caller charges include source/search preparation and are separate from
the native arithmetic snapshot. This mode does not render a CLI model or independently
validate a source witness. Keep ordinary CLI records for publication/control evidence.

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
`cases.json` uses the maintained offline source-verifier record format; `sources/`
contains its hash-named original bytes. Independently check it with
`verify_source_results.py --source-root <sources> --witness-only` on the analysis host.
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
and timing probes do not run as part of ordinary repository checks; follow the
[development validation policy](../docs/development.md#validation).

`source-ownership` contains self-authored original MiniZinc mixed integer/real
product controls, including an unrelated finite distinctness constraint and an
excluded integer value. Run CP/LS with default and exact modes; the harness records
the original MiniZinc source check separately from compiled FlatZinc semantics.

`SourceRecordCheck` also accepts `checker=minizinc` for saved MiniZinc records. It
checks source hashes and pins the retained DZN assignments into the original model
using the recorded compiler configuration. It records this original-source result
separately from checking exact-coordinate comments against compiled FlatZinc.

