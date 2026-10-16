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
`param=source-validation=true` opts MiniZinc integer and XCSP3 runs into independent
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

Replace `123` with the returned job ID. `lab cases` prints a tabular summary;
the HTTP `GET /experiments/<id>/cases` endpoint supplies the JSON array consumed by
`tools/analyze_lab.py` and `bench mine`. `lab fetch` downloads records and raw
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

`com.eignex.klause.bench.profile.CliCompilationStudy` captures fresh paired CLI
invocations on a Linux profile worker. Required arguments are `selection=<https-url>`,
`sha256=<selection-sha256>`, `plan=<https-url>`, `plan-sha256=<sha256>`,
`control=<40-character-git-sha>` and `candidate=<40-character-git-sha>`. Optional
`families=<https-url>` and `families-sha256=<sha256>` must appear together. Metadata
URLs have no credentials, query or fragment. Selection and plan inputs are bounded
to 1 MiB each; family declarations to 16 MiB.

Selection entries contain `suite`, `problem`, `format` and original `sourceHashes`
(`model`, optionally `data`). The catalog source bytes must match every digest.
Plans explicitly contain `mode`, original input ordinals in `inputs`, `repeats`,
`timeout` in milliseconds, `engines` and `profile`. Optional `params`, `lp`, `exact`,
`recipe`, `caseSuffix`, `orderOffset` and `solverSeed` preserve distinct controls;
the default solver seed is 3. Source validation is fixed on for MiniZinc/XCSP3 and
cannot be overridden by plan parameters. Case names must be unique across all
plans. Bounds are 128 inputs, 100 repeats, one hour per invocation, 4096 planned
arm invocations, 128 MiB per original source and 1 GiB total source reads.

Each pinned revision is built in a separate owned detached worktree with
`:klause-cli:installJvmDist` and the installed bench. Equal revisions are allowed
for repeated source controls. Both pinned harnesses must support
`KLAUSE_CLI_PREFLIGHT_OPTS`; an older harness is rejected before building.
Before/after build cleanliness, actual heads and
CLI/harness/JDK manifests are retained; installed-file metadata is checked around
every invocation. Arm order alternates by original ordinal, repetition and offset.
Both arms use one pinned CPU, one visible processor, Serial GC, a 3 GiB CLI heap
and one thread for the configured native math libraries. Profiled cases retain
threshold-zero compilation/phase/inlining JFR, compilation XML and class-loading
source logs; unprofiled controls retain the ordinary CLI options without those
observers. `KLAUSE_CLI_PREFLIGHT_OPTS` selects the unprofiled options for the
version check, while `KLAUSE_CLI_OPTS` retains the measured solve options. The
capture sets the same heap, collector and processor controls in both. Each case
records both option strings, and installed provenance retains their separate
identities. Version checks cannot rotate or overwrite the solve recording files.
Initial environment/system-property JFR events are disabled; explicit
controlled options and installed provenance remain recorded.

Every attempted case retains its command, raw streams, watchdog/exit, original
benchmark record, source-hash check and recording-presence fields. Ordinary child
failures do not discard later planned cases. MiniZinc/XCSP3 checks follow the
ordinary bench path; separate Boolean/SMT/MPS verifier setup and runs follow all
planned measurements. Their source/dependency identities, outputs and exits stay
separate from capture completion and source validity. Owned builds and checker
environments are cleaned, with cleanup failures retained. Complete output is
compressed through the capture archive. Choose explicit cohorts that fit the lab
return limit; an incomplete returned archive establishes no complete study.
Capture alone establishes no source proof, root-cost comparison, allocation/RNG/
work equivalence or performance benefit. Analyse complete recordings and run the
required independent runtime and state controls separately.

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
`parser=admission` requires linear factor admission; `parser=factor` exercises the
existing library SMT factor facade, including representable bounded mixed products.
`entry=source engine=cp|ls` instead calls the public source-strategy satisfaction
facade with the same selected providers, containing token and seed. This mode rejects
objectives and records its actual native backend beside the requested entry and engine;
`entry=pipeline engine=cp` retains the direct pipeline comparison. Compare matched
revisions and check the recorded backend to distinguish finite and open controls.
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

`bench.profile.ProjectionProviderProfile` compares direct built-in projection opening,
captured default opening and alternate clause propagation/scoring plus equivalent
integer relaxation emission. It alternates control order after a warmup, records
allocation bytes and wall nanoseconds separately for descriptor selection and
mechanism opening, and retains the executed revision, source and classpath hashes,
JDK, cores, seeds and budgets. Arguments are
`factors=N rounds=N openings=N seed=N processors=N`; defaults are 128 factors,
seven measured rounds, 100 openings, seed 3 and one processor. The probe opens a
private measurement JVM with `ActiveProcessorCount` when the launcher's visible
processor count differs; its JFR is `projection-provider.jfr`. Run on one AWS
machine with `parallel: 1` and a pinned revision through a lab profile job.

Its exact Boolean/integer source fixture retains every original-domain assignment.
An independent Python process checks complete source enumeration, scoring acceptance
and minimum/maximum original-direction integer objectives for both providers. Corner
controls cover inert deduction, unsupported/uncheckable sources and omitted real
relaxation rows, with exact rational source coordinates and objective directions.
Prepared finite invocation controls retain the selected bundle through presolve and
root baking. A source-associated unary-table fixture maps source integer 2 to CP
integer 0, checking inert deduction, weighted scoring and scaled relaxation selected
by original factor identity. Its bounded CP projection is enumerated completely;
unused open source columns are checked in witnesses and are not enumerated.
These checks establish these fixtures' source behavior; they export no proof file and
do not certify arbitrary custom providers. Opening and allocation measurements are
preparation diagnostics. Source checking and optimization controls occur outside the
timed spans. `source-check.json` records the checker and input hashes; missing or
failed checking cannot count as parity evidence.

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

`com.eignex.klause.bench.profile.SourceEvidenceProbe` checks produced quadratic
evidence for the exact IR fixture schema `real-box-square`: one real coordinate in
an integer-ended closed interval, objective `coefficient*x*x + constant`, and an
explicit source sense. It accepts the source JSON bytes as its first argument and
a `FiniteEngine` name as its second. Extra source fields and inexact endpoints are
refused. Submit a profile specification through the configured lab client at a pinned
revision. Include `machines: 1` and `parallel: 1` in the initial specification; changing
machine count after submission does not retract machines already launched.

```json
{
  "name": "source-evidence-probe",
  "profile": {
    "main": "com.eignex.klause.bench.profile.SourceEvidenceProbe",
    "args": ["<exact real-box-square source JSON>", "BACKTRACK"]
  },
  "base": {"ref": "<revision>", "timeout": "600000"},
  "machines": 1,
  "parallel": 1
}
```

For example, the first argument can be
`{"semantics":"klause-ir-exact-v1","kind":"real-box-square","lower":-2,"upper":3,"coefficient":1,"constant":0,"sense":"MINIMIZE"}`.
Save the specification outside the repository, then use
`LAB_URL=https://lab.eignex.com ./deploy/lab run /absolute/path/to/profile-source-evidence.json`.

The probe recognizes a rational endpoint represented by an algebraic root only
after exact polynomial evaluation and isolating-interval membership. Native objective
and bound values must equal the exact original objective at that point before serialization.

The probe saves original bytes and the actual result/capture payload under `$OUT`,
then runs the packaged independent Python fractions checker in a separate process.
`independent-check.json` records checked or unavailable coverage and source, payload
and checker identities. `runtime-provenance.json` records the revision, JDK and
classpath artifact hashes. An unavailable capture cannot count as checked evidence.
This fixture checker is not general proof-file export, a verdict reference comparison
or a performance measurement. Retrieve records with `lab files`, `cat` and `fetch`;
keep generated output outside the repository.

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
suite caps. Type/syntax errors and array index-set mismatches exclude only the
identity checked. Compilable siblings remain eligible. Compiler timeouts, missing
files, crashes and unclassified errors stop selection; they cannot establish source
incompatibility. Compilation uses seed 1 and never invokes a solver. Checks run only
on candidates reached while filling a family cap, after source-kind filtering;
seeded ordering remains deterministic. Compatibility preparation precedes sharding. Catalogue `suite` selection applies
its admission policy before provider property/default caps as well. Raw identity
lookup (`uncapped`, named sets and `solve-one`) bypasses admission and provider
property caps for compatibility suites, retaining every original identity.

`select` emits excluded identities as JSON lines on stderr with the reason,
diagnostic, compiler exit, original model/data hashes and compiler/library identity.
Selected JSON lines carry `selectionPolicy=minizinc-compile-compatibility-v1` and
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
a lab profile job on AWS. Capture settings precede `--`; the remaining arguments go
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
and strict improvement in the original direction. It uses authoritative coordinate comments when supplied and
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

`com.eignex.klause.bench.profile.QuadraticSourceProbe` runs exact real box sources
through the public quadratic API. Pass `source=<JSON>` with `semantics=klause-ir-exact-v1`,
`reals` containing rational `lower`/`upper` declarations, and `objective` containing
`sense`, rational `constant` and `terms` (`coefficient`, `first`, optional `second`).
Declarations must have an exact binary64 IR projection. `controls=BACKTRACK:1,MIXED:1,MIXED:4`
selects engine/lane pairs; `maximumWork` bounds each invocation. The probe saves
original bytes, actual witnesses/results, source capture outcomes and hashes under
`out` or `$OUT`, then runs `tools/verify_quadratic_box_results.py` with `python=python3`.
That independent fractions checker evaluates original coordinates and objective,
checks supported interval bounds and attainment, and withholds unsupported algebraic
or stronger bound claims. This does not verify general quadratic rays or proof exports.

`com.eignex.klause.bench.profile.OpenSourcePublicationProbe` accepts the same box JSON
with affine terms (`coefficient`, `first`) and exercises the ordinary open pipeline's
public accepted-incumbent callbacks. Exact captured coefficients and constant retain
source units separately from the execution projection. Engine/lane `controls` select
complete or heuristic paths. Saved receipts include original coordinates, values,
versions and final identity; the same fractions checker verifies each witness and
strict original-direction ordering. Missing typed bounds remain unavailable rather
than acquiring proof authority from an accepted point.

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

`com.eignex.klause.bench.profile.XcspArchiveReplay` diagnoses archived JVM CLI
distributions on an owned AWS profile. It takes one canonical JSON request,
gzip-compressed and base64-encoded, with these fields:

- `bucket` and `region` identify the lab's existing S3 build cache.
- `archives` contains up to four objects with unique `id`, revision-addressed
  `key`, exact quoted `etag`, and optional `sha256`. Conditional downloads retain
  the original archive, metadata, SHA-256 and every installed regular-file hash.
- `sources` contains up to twelve objects with unique `id`, original XCSP `text`,
  `sha256` and a complete `witness`. The current guarded source checker must
  accept each satisfaction witness before an archived solver runs.
- `cases` contains up to twelve objects with unique `id`, `archive` and `source`
  identities. Optional `args` accepts engine and parameter pairs, `seed` defaults
  to 3, and `timeoutMillis` defaults to 60000 and cannot exceed it.

For a class ablation, a case's optional `overlay` names an `archive` and a list
of `classes`, restricted to Klause class paths. Only those classes precede the
baseline distribution on the classpath. Optional `wallMillis` replaces the sole
`2000L` constant in each of the archived `Portfolio` and `Portfolio$Companion`
classes with a value from 2000 through 60000. Use this diagnostic intervention
only after auditing those classes' default slice policy. Class hashes and edited
byte offsets are retained; these cases are explicitly instrumented executions.
An overlay may additionally select `methods` by unique name in a single class.
The JDK class-file API copies only those methods into the baseline class, requires
matching descriptors and verifies the result. Donor and baseline bytes are retained.
The same-method baseline control checks that the intervention preserves the original
behavior. Whole-class overlays require compatible internal ABIs; worker failures
make a case ineligible for causal attribution even if the CLI exits successfully.

The request is capped at 60 KiB compressed and 2 MiB expanded. Each archive is
capped at 64 MiB compressed and 96 MiB expanded, and traversal, links, duplicate
files and members outside installed distributions are rejected. Each child has
bounded output and a watchdog. CLI JVMs use one active processor, a 3 GiB heap
and SerialGC; source checkers use 512 MiB and twelve seconds. The containing
profile owns process-group cleanup through the source-capture lifecycle.
Retain the full profile output: source controls, current harness/runtime hashes,
archived bytes, intervention classes, raw streams, process exits and results.
An archived UNSAT claim contradicting an accepted source witness identifies a
defect; it supplies no independently checked refutation. Storage timestamps and
requested revisions do not establish the historical process's runtime identity.

### Offline source verifier

`com.eignex.klause.bench.profile.MpsLinearProof` accepts original MPS paths and captures the
ordinary exact-source optimization result, original coordinate mapping, rows and derivation.
It runs [verify_mps_linear_proof.py](tools/verify_mps_linear_proof.py) in a separate Python
process using only standard-library exact fractions. The checker parses original
linear MPS bytes, checks every captured premise against source rows or integral declarations,
eliminates an exact objective auxiliary, and validates nonnegative dual combinations,
integer branch coverage or an integral improving recession step. Unsupported source features
and missing derivations fail closed. This checks mathematical claims rather than timings.
Conditional rows retain optional `integerPins` maps from original `Integer/id` coordinates
to exact integer strings. A cited pin must follow from original singleton bounds and the
current exhaustive integer branches; an incumbent cannot authorize a guarded global bound.
Witnesses check the original indicator implications. Guarded recession steps require fixed
guard coordinates and the enabled rows' recession signs. Capturing an unsupported producer
derivation remains unavailable.
The self-authored inputs in `smoke-corpus/linear-authority` cover a constant objective,
a coupled integral/real ray, an objective coefficient beyond binary64 precision and
an integer gap requiring exhaustive split coverage. Separate incumbent/value and
global-bound derivations retain their own checks; a ray base need not be the incumbent.

```sh
lab profile com.eignex.klause.bench.profile.MpsLinearProof <ref> "check exact MPS claims" aws 900 \
  klause-bench/smoke-corpus/linear-authority/constant.mps \
  klause-bench/smoke-corpus/linear-authority/recession.mps \
  klause-bench/smoke-corpus/linear-authority/decimal.mps
```

The profile output retains original bytes/hashes, claim captures, checker results, installed
jar hashes and runtime/options. Keep the lab's executed commit and setup provenance with
those files. A failed checker or incomplete profile supplies no validated terminal claim.
`--route=numerical` invokes the direct numerical LP/MIP request without theory
fallback; the default `--route=theory` retains the ordinary exact-source strategy.
The capture records numerical source operations and floating work separately from
the route label. CLI stream captures establish frontend selection independently.
Numerical captures retain conditional integer source pins on indicator rows;
branch restrictions authorize their use independently of the incumbent. The
fraction checker checks those pins and original enabled rows, with fixed guard
coordinates required for recession authority.
Leading options select the real public route: `--engine=cp|fixed|mixed|ls|alns`,
`--cores=N`, `--checks=N`, `--work=N` and `--timeout=MS`. Defaults are cp, one core,
1000 checks, 2000000 work and 3000 ms per input. Complete routes must return typed
linear authority; witness-only routes must withhold terminal claims. For example,
`--checks=1` on `integer-gap.mps` checks an incomplete invocation's independently
certified global bound. These are explicit profile controls, separate from lab scheduling.

`com.eignex.klause.bench.profile.SmtLinearProof` uses the same route/budget options on
original SMT-LIB paths. [verify_smt_linear_proof.py](tools/verify_smt_linear_proof.py)
independently parses an unguarded affine subset: numeric and Boolean declarations,
affine inequalities/equalities, one objective and `check-sat`. Unsupported constructs
or auxiliary coordinate mappings fail closed. Exact fractions check strict bounds,
closure endpoints, feasible approach bases, fixed integer/Boolean approach coordinates,
integral recession steps and both alternatives of exported integer/Boolean splits.
Integer premise strengthening must follow exact integer rounding of an original row.
Original source bytes, separate incumbents/values, exported premises/proofs, runtime and
installed-jar hashes remain in the profile output. Self-authored `strict-min.smt2`,
`strict-max.smt2`, `boolean-limit.smt2`, `integer-strict.smt2` and `mixed-ray.smt2` under
`smoke-corpus/linear-authority` exercise these source semantics. Boolean coverage uses
an unconstrained declared Boolean beside a strict real row; guarded formulas are outside
this independent parser's subset.
`--condition=path.json` captures caller restrictions by original declaration name:
`bools` contains Boolean pins; `lower` and `upper` contain exact integer or rational
strings. The profile retains those bytes and their hash and passes the same file as
the checker's optional third argument. Conditional assumptions, points, approach
endpoints and rays are checked against those separate caller bytes; a conditional
proof cannot be checked as a parent proof by omitting or weakening its restrictions.
`conditioned-affine.smt2` and `conditioned-affine.condition.json` exercise a Boolean
pin, a real pin at `1/3` and an integer pin at `2^64`.
`exact-real-pin.smt2` and its condition file exercise scoped witness-only receipts
at `1/3`; these routes must withhold bounds and terminal claims.
`--route=authority` isolates the public source-authority entry point with cp and one
core. The default `--route=request` exercises ordinary request and portfolio execution;
the output records which entry point produced the claim.
`admission-box.smt2` is a self-authored bounded real source beyond the numerical
authority's column ceiling. With `--work=8` it checks the retained source witness
from feasibility fallback; `--work=1` checks that the authority attempt cannot renew
the containing allowance. Neither control may publish a terminal proof after decline.

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

`bench.profile.SourceRecordCheck` runs this verifier on saved records through an
AWS lab profile without rerunning the solver. Pass `bundle=<https URL>` and
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
`DIMACS`, `WCNF` and linear `OPB`/WBO records against original source bytes in that same
hash-named directory. It accepts complete signed-literal assignments, OPB variable
names and WCNF bitstrings. Clauses, hard weights, Boolean integrality and integer
objectives use exact arithmetic. WBO checks evaluate weighted soft-relation
violations and enforce the strict soft-top ceiling on that violation cost. UTF-8
comments preserve original byte hashes.
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
The exact `coreGuidedLowerBound` statistic is checked by excluding source objectives
below that floor, including records without an incumbent. `checkedLowerBound` records
only a completed independent check; an undecided floor uses `boundCheck=unknown`.
Floor telemetry and floor checking do not establish an optimum without a matching witness.
Errors are recorded per case and make the command exit unsuccessfully. The checker
supports JSON, gzip and xz inputs and preserves cases with no claimed verdict.
Pass `checker=boolean` to the saved-record `SourceRecordCheck` AWS profile to run
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

### Native VM layout metadata

`com.eignex.klause.bench.profile.NativeVmMetadataVerification` inspects exported
serviceability tables from the pinned Linux x86-64 JVM library in a separate
unprivileged Python process. It verifies the library hash before loading and after
reading. `dlinfo(RTLD_DI_LINKMAP)` supplies the loaded object's public address
member; only that first `Elf64_Addr` is read after checking its readable mapping.
No private loader structure members or host padding are mirrored.

The loaded program-header bytes must match the pinned ELF file at the correct
file offset. Metadata reads intersect loader-owned readable `PT_LOAD` segments
with readable exact-file or private anonymous kernel mappings. Unrelated,
ambiguous, unsupported or unreadable ranges remain refused. Exported offsets,
strides and target type widths bound every field/table read. Raw tables, query
outputs, mapping snapshots, hashes, errors and exits remain in the capture.

The diagnostic needs no worker C compiler, creates no JVM in its metadata process,
reads no compiler-task instance, attaches no probes and changes no settings.
Layout metadata alone supplies no compilation-root attribution, clock/loss proof,
elapsed time, code-size reconstruction or compiler saving.

### Native VM symbol availability

`com.eignex.klause.bench.profile.NativeVmSymbolVerification` reads the pinned
Linux x86-64 library's ELF symbol tables in an unprivileged Python child process.
It verifies the open file and path identity and hashes before and after inspection.
Section, linked string and symbol table reads have explicit file and size bounds;
unsupported extended indexes, compressed tables and malformed names are refused.

The diagnostic retains every raw symbol record whose name contains
`invoke_compiler_on_method`, `post_compiled_method` or `total_size`, including
local, duplicate, undefined and non-function entries. Missing names are reported
only as absent from the available tables, with full versus dynamic table coverage
recorded separately. Source-name matches establish no exact C++ signature or ABI.

Pass up to sixteen distinct ASCII source identifiers to select additional query
groups. Omitted arguments retain the three defaults. Wildcards, C++ punctuation,
empty or oversized identifiers are refused; no mangled symbol name is invented.

The capture includes original record bytes, section/table hashes, scan completion,
partial records, errors and exits. The reader neither loads nor invokes the library,
attaches no probes and supplies no task/root, code-size, timing or savings proof.

### Captured native symbol signatures

`com.eignex.klause.bench.profile.NativeVmSignatureVerification` accepts
`report=<https URL>` and `report-sha256=<hash>` for a complete retained symbol
report. The bounded download must match its hash and the pinned JVM library.
The child hashes the target library without loading or invoking it.

The public `__cxa_demangle` API supplies C++ names through the worker's installed
provider. Its entry must bind to one exact named executable file mapping and
loader-owned segment, with provider hashes and loaded-header bytes verified.
Only the API-reported allocated buffer is read, with an explicit size limit;
returned buffers are released on success and failure. Status, allocation size,
provider metadata and partial/error captures are retained.

Every original symbol record remains. Exact matches to pinned broker, nmethod,
compiler-thread lifecycle, runtime initialization, queue notification and
requesting-thread source signatures are listed separately, preserving duplicates
and excluding undefined or non-function definitions. Other bounded query groups
retain their demangled records without a source-signature claim. A demangled
signature supplies no proof of the
actual argument registers, live task pointer, root association, clock/loss,
code-size result or compiler saving. Validate those boundaries before probing.

### Native external debug-file identity

`com.eignex.klause.bench.profile.NativeVmDebugImageBinding` accepts no arguments.
It checks the pinned Linux x86-64 JVM library before downloading the official
Temurin 25.0.4.1+1 debug image. The archive URL, byte length and SHA-256 are fixed
in the reader. Its bounds are 512 MiB compressed, 2 GiB of declared member bytes
and 4096 regular-file/directory records. Unsafe paths, duplicate members and
ambiguous `libjvm.debuginfo` members fail explicitly.

Only that debug file is extracted, into temporary storage outside the capture.
Both ELF files retain their exact file hashes, section and symbol metadata and
raw note/debug-link records. Binding requires one matching GNU build ID on each
side and the target's debug-link filename and CRC for the complete external file.
Both file identities are rechecked after inspection. Archive URLs, hashes and
member metadata support recovery; downloaded archive and native debug bytes are
removed when the child exits normally, including failures. The parent allows
300 seconds and archives raw output on failure.

The diagnostic does not interpret DWARF types or locations, load either native
file, invoke a decoder, run a target or read private fields. A bound external file
establishes no calling convention, field layout, control-flow/runtime coverage,
root census, clock agreement, generated-code size or compiler saving.

`com.eignex.klause.bench.profile.NativeVmDwarfRecords` accepts no arguments or
`context=true`, or `task-lifecycle=true`. It repeats the external-file binding before inspecting the original bound debug-file
hash. A private hash-pinned pyelftools 0.33 wheel supplies the pure-Python reader;
package member hashes, imported module paths and license texts are retained and
rechecked. Installation is local to temporary worker storage. Neither native file
is loaded or invoked; supplementary-file following and relocations are disabled.

The reader retains compilation-unit headers and visits entries only for
`compileBroker.cpp`, `compileTask.cpp`, `nmethod.cpp` and `compilerThread.cpp`.
All matching compiler type/function/inline records, direct children and referenced
type/origin/object-pointer records remain explicit, including duplicate candidates,
missing names, unknown parents and failed references. Type-unit and supplementary
references remain unresolved. Limits are 4096 indexed compilation units,
2 million visited entries, 2048 selected records, 16384 graph nodes, 512 direct
children, 128 scope levels and 32 MiB of retained metadata. The parent allows
600 seconds. Location expressions/lists are retained as attributes without
evaluation. Selected-unit coverage establishes no unique source layout, calling
convention, register/field read, complete root census or compiler saving.

The task-lifecycle mode retains the existing compiler selection and additionally
requests `CompileTaskWrapper`, task allocation, initialization and free functions,
and wrapper constructor/destructor names. The capture records its exact type and
function name plan. Matching declarations, physical functions and inline records
remain separate, with all aliases and reference failures retained. This mode also
includes the bounded source/range/location contexts below and keeps the same four
compilation units and resource limits. Selection supplies no lifecycle coverage,
entry ABI, task generation, private-read authorization or compiler-cost proof.

`context=true` also retains each compilation-unit root, line-table header and
the header file/directory entries referenced by declarations and inline calls.
DWARF 2–4 headers use one-based file/directory tables with directory zero pointing
to the compilation directory; DWARF 5 tables use zero-based indexes. A version-5
file index of zero retains its candidate and the
[specification ambiguity](https://wiki.dwarfstd.org/DWARF5_Line_Table_File_Numbers.md);
it establishes no source-path binding. Line programs are not executed, so dynamic
file definitions and indexes outside the header remain unresolved. Raw declaration
and call attributes, file names, directory names and failed header parses remain
available without loading source files from debug paths.

Range lists and parameter/frame/return location lists retain the pinned provider's
decoded base, relative/absolute range and expression records, including default
locations. The containing unit or entry accompanies every provider lookup. Location
expressions, instruction coverage, entry registers and lifetimes are not evaluated.
Each parse shares a 256 KiB read budget and 65536-operation budget across involved
sections, preserving absolute section offsets. Header/list output is bounded to
4096 entries, contexts to 8 MiB and the containing inventory to 32 MiB. Resource
limits fail the capture; unsupported encodings and missing sections remain explicit
per-record errors. All referenced-unit contexts are retained. This mode requires
its own reader source identity and establishes no field layout, calling convention,
source binding, complete root census or compiler saving.

`com.eignex.klause.bench.profile.NativeVmDwarfRecordReview` accepts `records=`,
`records-sha256=` and `producer-sha256=` for a saved inventory from the pinned
reader. It preserves the complete input and every selected type/function/inline
candidate. Member review follows bounded typedef/qualifier chains and reports
constant storage widths and member offsets within declared owner bounds.
Bit fields, missing types, unknown widths, expression locations and conflicting
definitions remain unresolved. CompileTask field concordance includes every
retained non-declaration, including incomplete definitions. Declarations and
duplicate candidates remain in the report. Parameter locations and ranges are
retained without evaluation; inheritance is not expanded. This review loads no
native file and establishes no actual field layout, entry calling convention,
live field read, complete root census or compiler saving.
Input is bounded at 8 MiB, output review records at 32 MiB, type chains at
32 records, and the parent allows 60 seconds. Partial reports remain failures.

`com.eignex.klause.bench.profile.NativeVmDwarfLayoutReview` accepts `context=`,
`context-sha256=`, `layout=` and `layout-sha256=`. Both saved inputs are retained
byte for byte before review. It accepts the pinned context-reader pair and one
original hash-bound exported-layout capture from the same JVM library. Context
input is bounded at 64 MiB, exported metadata at 1 MiB and output at 32 MiB;
the parent allows 60 seconds. This profile loads no native library.

The review retains all type/function/inline candidates and compares every retained
non-declaration of global `CompileTask`. Its size and five exported anchors must
agree with the independent metadata. Requested members must belong directly to
that owner and match the pinned source header path, declaration line and compiled
storage type. Signed integer widths come from the exported primitive type;
pointer widths must be explicit in both records and the `Method` target resolved.
Bit fields, overlapping declared fields, incomplete unit coverage, name errors,
duplicate exports, unknown parents and conflicting definitions prevent proof.
Concordance is limited to the pinned library/debug files and selected compiler
units. Entry calling convention and private-field-read authorization remain false;
layout concordance supplies no live read, complete root census or compiler saving.

`com.eignex.klause.bench.profile.NativeVmEntryAbiReview` accepts `context=`,
`context-sha256=`, `layout=`, `layout-sha256=`, `entries=` and `entries-sha256=`.
It retains the three exact supported saved captures before review: bound compiler
DWARF contexts, proven task layouts and executable entry records. Each input is
bounded at 32 MiB, review records at 32 MiB and child execution at 60 seconds.
The original entry producer's five default queries remain explicit when its
capture lacks a query field. No native library or decoder is loaded.

The fixed plan covers the canonical `CompileBroker::invoke_compiler_on_method`
and `nmethod::post_compiled_method` symbols only. A complete symbol span and exact
DWARF entry must agree. Concrete parameter ownership, pointer widths/targets,
artificial object references and all matching descriptions at that entry must
agree with the source signature and normal AMD64 register convention. Direct
`DW_OP_regN` and `DW_OP_regx` descriptions are recognized. Relative location lists
require an explicit compilation-unit base; default locations apply only when no
bounded range covers entry. Empty covered descriptions remain unavailable.

Other aliases and query groups remain retained outside that plan. Conflicting
definitions or canonical symbol addresses, unresolved origins, partial spans,
split function ranges, entry-value/composite expressions and non-normal calling
conventions remain unproved. The result supplies no task-lifetime, control-flow,
root-census, clock, code-size or compiler-saving proof. Private live-field-read
authorization remains false. Register numbering follows the
[AMD64 ABI](https://gitlab.com/x86-psABIs/x86-64-ABI/-/blob/master/x86-64-ABI/low-level-sys-info.tex),
and canonical source symbol encoding follows the
[C++ ABI](https://itanium-cxx-abi.github.io/cxx-abi/abi.html#mangling).

`com.eignex.klause.bench.profile.NativeVmTaskCallReview` accepts four saved input
URL/hash pairs: `context=`, `abi=`, `entries=` and `transfers=`, each with its
`-sha256` option. Inputs are bounded at 16 MiB each and 32 MiB combined; output
is bounded at 32 MiB and child execution at 60 seconds. All original inputs,
aliases, transfer records and unresolved cases are retained. No library or decoder
is loaded by the saved-data review.

The fixed caller plan binds `CompileBroker::compiler_thread_loop` to its pinned
source declaration and complete physical span. Broker and post entries must match
the original entry ABI proof. Required task-wrapper destructor and task-free
definitions remain explicit. A direct near call must encode the bound broker
target and return to a retained caller instruction boundary. Tail, indirect,
prefixed and unresolved transfers supply no normal-call proof. This establishes
the specific call/continuation binding only. The caller's source wrapper lifetime
contract remains separate from transitive callee effects, kernel return probes,
task generation and live-read validation. Private reads, complete control flow,
root census, clocks, code size and compiler savings remain unproved.

`com.eignex.klause.bench.profile.NativeVmTaskCallFlowReview` accepts the same four
fixed saved input URL/hash pairs. It rechecks the complete caller/source and
canonical entry plan, then reads the pinned JVM library as file bytes. A private
hash-pinned Capstone 5.0.6 provider runs with detail enabled, skip-data disabled
and diet mode refused. The target JVM library is never loaded or invoked.

Every fresh instruction must match the original address, bytes, mnemonic and
operand text. Group IDs/names and typed register, immediate and memory operands
are retained with every caller alias. Private provider and Python binding files
are checked against the wheel before and after use. Bounds are 16 MiB per input,
32 MiB for combined inputs, 32 MiB for output, 20,000 detailed instruction records and a
90-second child allowance. Failures retain original inputs and partial diagnostics;
an oversized derived inventory is explicitly withheld without a success claim.

The normal linear model checks direct relative transfer encodings and targets,
instruction boundaries and every incoming edge to a selected broker continuation.
Indirect, prefixed, unsupported, incomplete or joined paths decline the guard.
Complete detailed metadata does not require a successful linear guard. A sole
incoming edge in this model proves no alternate native entry, transitive callee
effects, dynamic return or global control flow. Native exclusive-entry and return
probe authorization flags remain false, as do task generation/lifetime, private
reads, kernel coverage, compiler roots, clocks, code size and saving claims.

`com.eignex.klause.bench.profile.NativeVmTaskLifecycleAbiReview` accepts five
saved URL/hash pairs: `context=`, `original-context=`, `layout=`, `entries=` and
`additional-entries=`, each with its `-sha256` option. Inputs are bounded at 16 MiB
each and 32 MiB combined, the review at 32 MiB and child execution at 60 seconds.
Original bytes, every entry, declaration, inline description and error remain
retained. No native file or decoder is loaded.

The fixed plan covers task allocation, initialization and free, plus both wrapper
constructor/destructor aliases. It rebinds every validated task definition and
member/type-reference closure from the original context into the lifecycle
context. Parser cache accounting may differ; semantic descriptions must agree.
Each canonical physical entry requires its complete bytes, source declaration,
concrete parameter inventory and object lineage. Split functions require the
canonical ordinary ELF symbol to start exactly one decoded range containing its
whole span. All ranges and base-address records are retained; overlapping ranges,
unknown bases and conflicting explicit entries remain unproved. The association
uses the physical function symbol. Missing explicit DWARF entry attributes are
not synthesized from range order, and explicit entry evidence is recorded
separately. Only the requested task pointers
and initializer compile identity receive direct entry-register proofs. Unused
locations remain uninterpreted. The allocator's normal pointer return class is
separate from execution or delivery of a return. Wrapper destruction binds a
boundary with no argument or field read; task association belongs to the current
compiler thread. Missing aliases, conflicting descriptions, partial spans and
unmatched physical definitions remain unproved. The complete calling convention,
compiled lifetime, task generation, kernel callbacks, private reads, root census,
clocks, code size and compiler savings remain unproved.

`com.eignex.klause.bench.profile.NativeVmTaskGenerationVerification` accepts no
arguments. It retains the fixed lifecycle read-plan and canonical compiler-entry
ABI reports before binding their complete symbols to freshly hash-checked library
bytes. Every fixed role, alias and needed parameter proof must remain complete.
Physical aliases share one probe while retaining every original reference;
conflicting names or shared locations between different roles prevent attachment.
Proof inputs are bounded at 4 MiB each and the complete physical probe set at 32.

The dedicated version child runs under the existing isolated observer lifecycle
and complete owned loss-counter controls. Entry callbacks record task pointers,
the initializer's signed 32-bit compile identity, the post-publication receiver,
and per-thread sequences. Destruction records a boundary with no argument read.
Allocation returns and target private fields are not read. Kernel lifecycle,
bounded public USDT identities, final sequences, raw JFR and compilation logs
remain retained, with Compilation and CompilerPhase thresholds at zero.

The raw capture does not reconstruct generations or prove pointer lifetime.
Missing, duplicate, unfinished and conflicting observations require a separate
saved-record review. Zero owned buffer-loss counters establish only that counter
scope; kernel probe delivery, generation/lifetime, private-read authorization,
complete roots, clocks, code size and compiler savings remain unproved.

`com.eignex.klause.bench.profile.NativeVmTaskGenerationReview` accepts `manifest`,
`manifest-sha256` and `archive` HTTPS inputs for a saved entry capture. It verifies
the complete archive membership and extracts the fixed required records without
running the captured child or attaching probes. Downloads are bounded at 4 MiB
for the manifest and 32 MiB for the archive; selected files are bounded at 16 MiB
each and 64 MiB combined. The reviewer rechecks every original proof input,
physical alias/source definition, library span and executed program.
The manifest binds saved bytes; accepting an observation also requires the
original capture's authenticated source and worker provenance.

The pinned observer emits compiler diagnostics on stdout before attachment.
The reviewer recognizes external-compiler and redundant `uint64` cast warnings
only when locations, source contexts and any column underlines match the original
script, including the empty terminal source line after a trailing newline.
External-compiler warnings retain their hints. The stdout readiness marker
bounds compiler output; stderr independently retains its `Attached` status.
The original observer command must match the source-bound invocation; no total
order across the two output streams is inferred. Diagnostic prefixes are bounded
at 4096 lines and 1 MiB; malformed, duplicated, interleaved, late or unexplained
output declines the guard.
All diagnostic bytes remain retained. Recognizing this rendering proves no
compiler-warning safety or probe coverage. The expected invalid-read control,
runtime warning checks, callback sequences and owned loss proof retain their
independent requirements.

Saved task entries retain their source names, signed compile identities, raw
pointer arguments, process/thread sequences and payload errors. Selected terminal
delivery and the complete owned loss-counter proof remain separate from global
kernel coverage. Initialization candidate edges retain every equal-pointer
initialization compatible with known per-thread sequence. Schema-2 graphs retain
each task entry once, with its native group event and primary-record index. Shared
pointer tables list every initialization entry; a candidate set excludes only
same-thread initializations at or after the task entry. Node timestamps preserve
all cross-thread relations without imposing an order or excluding hidden reuse.
Null tasks and orphan frees remain unresolved.

Observed wrapper nesting retains unfinished and orphan callbacks. Public identity
candidates preserve the independent JFR compilation records, all recorded phase
and inlining events, and the original XML states. Wrapper and JFR identity tables
refer to the retained native groups, task entries and recording-event positions
instead of copying those records. A unique candidate does not
establish wrapper identity, broker return, task generation or compiled lifetime.
Raw inputs remain retained on malformed data, resource failure or proof decline;
compact JSON output retains the same 64 MiB cap; oversized derived inventories
are withheld with an explicit failure. The review
does not authorize private reads, complete roots, elapsed clocks, code size or
compiler savings.

### Native executable entry bytes

`com.eignex.klause.bench.profile.NativeVmEntryVerification` retains bytes for the
three default symbol queries, `CompileQueue` and `CompileTaskWrapper` from the
pinned Linux x86-64 JVM library. Optional arguments select up to 16 distinct ASCII
source identifiers, each at most 128 characters, using the symbol reader's query
rules. Invalid queries fail before downloading the decoder. Each function's complete
declared span must fit an allocated executable section and one matching file-backed
executable segment.
The capture retains at most 16 KiB per function and 256 candidate records, with
explicit truncated, non-function, undefined, zero-size and per-record error states.

An unprivileged child downloads the hash-pinned Capstone 5.0.6 wheel into the
capture directory. Bounded extraction rejects unsafe members; the imported
binding and native provider must come from that directory. Package member hashes,
provider identity, raw input bytes, decoded instruction boundaries, undecoded
suffixes and failures are retained. There is no global installation or target
library loading. Linear decoding establishes no control-flow or argument proof;
do not derive private field offsets, task lifetime or compiler savings from it.

`com.eignex.klause.bench.profile.NativeVmEntryTransferReview` accepts
`entries=<https URL>`, `entries-sha256=<hash>` and `producer-sha256=<hash>`.
Authenticate the original capture and its exact reader source before supplying
these hashes; a reader version need not match the current source. The retained
download is bounded to 32 MiB. Captures without an explicit query list are accepted
only for the pinned original reader hash and its five fixed source queries; both
saved candidate groups must match that contract. Unknown producer versions cannot
infer queries from available records. The child rechecks symbol-owned bytes against the
same pinned library and validates every saved linear instruction boundary and
byte slice, without decoding again or loading the target library.

The review retains every original entry, failure, unrecognized instruction and
indirect operand. Recognized explicit transfer syntax records direct operands
and all captured definitions at that address, preserving aliases. Targets inside
the retained prefix, outside its linear boundaries, in an unretained symbol span
or outside the symbol remain distinct. This is a syntax inventory: it establishes
no reachability, alternate decoding, indirect target, callee return, calling
convention, inlining cause, runtime coverage, full-root census or compiler saving.

### Captured source and executable entry associations

`com.eignex.klause.bench.profile.NativeVmBoundaryEntryBinding` accepts
`entries=<https URL>`, `entries-sha256=<hash>`, `signatures=<https URL>` and
`signatures-sha256=<hash>`. Each download is bounded to 2 MiB and retained byte
for byte. Inputs must come from the reviewed entry and signature readers, carry
their exact producer hashes and identify the same pinned JVM library. Authenticate
the original captures and their provenance before supplying their hashes.

The child associates whole original symbol records across both captures and
rechecks their bounded executable bytes against the pinned library. It retains
all original records, references every association and groups aliases of one
physical definition. Missing records, incomplete inspections, partial symbol
tables and distinct physical definitions remain explicit. Bounds are 256 entry
records and 4096 association pairs across all query groups.

This diagnostic neither loads the target nor invokes the decoder or demangler.
A unique captured association supplies no calling-convention, private-field,
live task/thread, full-root, clock/loss, code-size or compiler savings proof.

### Native compiler boundary lifecycle

`com.eignex.klause.bench.profile.NativeVmBoundaryLifecycleVerification` runs a
distinct original-user Java startup after a pinned observer attaches. It requires
the hash-pinned source/entry association and executable-entry reports. Every
selected boundary must have one exact source contract, physical location and
probe name; all referenced bytes are rechecked against the pinned JVM library.
Conflicting names, cross-boundary aliases and incomplete inputs fail before
attachment. The observer package is hash-checked before extraction.

Compiler-thread entry/loop, interface/runtime initialization, requesting-thread
compilation/native wrappers, queue notification, runtime shutdown and dynamic
thread creation have entry/return callbacks. Those callbacks read no argument
registers, return values or private fields. Public compilation USDT identities
retain their bounded raw fragments. Selected-process exec, fork from any of its
threads, and thread exit share the guarded per-thread sequence stream with native
callbacks. Complete terminal snapshots and an ordered drain remain required.

The remote review rechecks original inputs, the exact executed script, child and
observer identities, namespaces, warning controls and the owned loss-counter
contract described under [live argument candidates](#live-native-argument-candidates).
It retains all malformed records, gaps, unmatched returns and unfinished frames.
A thread exit does not replace a missing return. Public compilation events retain
their observed boundary contexts and every JFR identity/thread candidate;
compiler labels compare without case and retain their raw values. Ambiguity
supplies no compile-ID or clock association. Raw JFR and compilation
logs remain independent inputs. Termination gives the capture process time to
clean up its managed child/observer before forced termination, and both successful
and failed captures retain their raw outputs in the compressed archive.

Boundary spans include waiting and supply no compiler elapsed-time metric.
Absent/inlined hooks, descendants, TID reuse, private task ownership, calling
conventions, complete root census, clock alignment, code-size and compiler savings
remain separate requirements. This diagnostic neither repeats a measured solver
workload nor transfers earlier outcome, allocation, RNG or work controls.

### Live native argument candidates

`com.eignex.klause.bench.profile.NativeVmLiveArgumentVerification` captures a
distinct original-user Java startup after the pinned observer attaches. The
observer's package runs in its private mount namespace; the selected JVM keeps
the original UID, GID, groups and namespace. No solver input or repeated measured
workload is run by this diagnostic.

The child validates canonical executable entries against the retained pinned
report and reads fresh exported layouts with the same library hash. Raw broker,
nmethod, queue and wrapper argument candidates remain unaccepted. Field reads use
only exported owner-bounded offsets and widths, guard null candidates and retain
their raw bytes. Return probes read task fields only for a selected task returned
from the queue; wrapper destruction and other returns never dereference a task.

One warning-enabled observer retains a labelled invalid-read case, per-TID
sequences, USDT identity lengths/bytes, returns, fork records and a drain marker.
After detachment, the end program exports every final per-TID sequence value and
its map cardinality/sequence total before the drain marker and map clear.
Key-presence guards precede ordinary map-value reads, distinguishing fresh
sequence keys from memory read failures. Raw boot/monotonic/realtime anchors
surround child release, exit
and observer drain. Threshold-zero JFR and the compilation log are independent
identity evidence; missing warnings or a successful exit alone prove no loss-free
capture. Descendant JVMs, unmatched or inlined hooks, truncated identities,
unfinished tasks and failed reads remain coverage gaps. Accept no ABI, live
pointer, task/root, return-size, clock or compiler-saving claim without the
corresponding independent validation.

`com.eignex.klause.bench.profile.NativeVmLiveArgumentReview` reviews saved captures
on a remote worker with `manifest=<https URL>`, `manifest-sha256=<hash>` and
`archive=<https URL>`. It verifies the manifest, archive and selected member hashes,
then extracts only the eight layout, launch, native record, JFR JSON and compilation
log inputs. Expansion bounds and regular-file membership reject links, duplicate
names and traversal. It never attaches an observer or reruns the captured JVM.

The review checks exported field widths and owner bounds against the original
tables, per-thread sequences and call lifetimes, raw candidate argument slots,
USDT identity bytes, JFR compilation/thread identities and compatible log records.
Omitted log levels remain unknown. A separately labelled invalid read must appear;
additional read errors or reported losses prevent argument corroboration. Every raw
record, unmatched lifetime and independent compilation identity remains available.
Remote summaries count retained identity matches, missing matches and lifetime
failures without relaxing capture-wide guards. Staged compile-ID, method/level/OSR
and native-thread matches distinguish absent JFR records from identity mismatches;
all same-ID candidates and unmatched JFR records remain in the report.
Terminal delivery additionally requires complete callback fragments, matching
final per-TID counters, a consistent complete-map total and one ordered drain
marker. Null candidates retain their explicit pointer record without field reads.
Truncated identity bytes can be completely delivered while their identity remains
unaccepted. Captures lacking final counters cannot pass this guard. Its scope is
sequenced native/USDT callbacks; unsequenced EXEC/FORK records and the global kernel
event-loss counter remain outside that proof. This guard does not erase unfinished
invocations or relax any existing read, lifetime or independent identity checks.
The pinned tracer's buffer limit includes a four-byte length header. The observer
reserves that header in addition to the 128-byte identity payload bound.
Per-record association supplies no complete calling convention, queue/root census,
loss-free capture, return-size semantics, clock alignment or compiler-saving proof.

Live argument captures also request an explicit final ring-buffer loss-counter
snapshot. A Linux x86-64 helper binds the original observer PID, start ticks,
executable digest and mount namespace, then retains its map descriptor. The map's
BTF must identify the single `__bt__event_loss_counter` variable at offset zero in
`.data.event_loss_counter`, with complete 64-bit CPU-slot dimensions. The helper
accepts unsigned integer metadata or the pinned bpftrace 0.27.0 `int64` signed
debug annotation: that version selects integer debug types by width, while its
counter declaration and reader use unsigned 64-bit values. Other signed type names,
integer encodings, widths and bit offsets are rejected. The binding retains the
debug name and encoding separately from the unsigned counter interpretation.
Ambiguous maps, unsupported layouts and incomplete ownership prevent child release.
The helper writes only a separately created control map, checking both zero data
and a nonzero highest slot through the same kernel lookup path.

After the observer exits and drains, the helper reads every retained counter slot.
Raw BTF, slot bytes, controls and process/map identities remain in the capture.
Saved review checks these bindings and the pinned helper source before admitting
a final counter value. Missing evidence or a nonzero final counter prevents
argument corroboration. Stderr summaries do not replace this explicit snapshot.
The counter covers tracer ring-buffer output; it does not prove probe invocation
coverage, root completeness, call lifetimes, clocks, code sizes or compiler savings.

`NativeVmLiveArgumentReview` also accepts `review=event-loss` with its three pinned
archive arguments to inspect a saved counter-map binding failure. This mode extracts
only the original helper output, errors, launch/extraction records and helper source.
It reports bounded BTF type graphs and integer encodings from the saved bytes,
including unsupported and missing types. It neither reads live maps nor reruns the
target, and does not relax the counter's type, ownership or acceptance guards.

With `review=log-identities` and the same three pinned archive arguments,
`NativeVmLiveArgumentReview` reproduces the original argument review in
`original-argument-review.json`, then reports complementary compilation-log
metadata separately. A task must have one explicit compile ID, method, level and
OSR identity under its compiler-thread log, whose start marker records the
captured native thread and process IDs. One direct `task_done` must agree with the
public compilation outcome. Duplicate task identities remain ambiguous before
outcomes are considered; missing levels remain unknown.

Successful publication corroboration additionally requires a unique global
`nmethod` identity with the matching compiler label and completed post callbacks
whose task and method metadata agree with the broker entry. Publication records
can omit the OSR BCI: that missing value remains unknown, while the task log and
native task/nmethod fields must carry the same exact BCI. Duplicate publication
identities remain ambiguous before compiler labels are considered. Failed
compilations can retain a matching thread-owned task outcome without publication
credit. Raw attributes, fragments and rejected candidates remain in the report;
`task_done`'s `nmsize` is retained as content-size metadata, not total code size.
The original JFR matches, unfinished lifetimes, read diagnostics, delivery and
loss-counter results are preserved unchanged. Complementary log metadata does
not establish argument binding, calling convention, root completeness, clock
alignment, total code size or compiler savings.

With `review=task-log`, the same profile retains and reviews the complete saved
`live/hotspot.xml` without invoking the argument, native-memory or JFR reviewers.
The pinned manifest and archive authenticate the unchanged XML. The original
hierarchical identity parser retains every task, queue, global publication,
fragment, orphan and unknown tag count. Failure reasons and publication-state
records retain their original document positions. Shared compile-ID candidate
tables preserve duplicates and unmatched identities without choosing a task,
publication, compiler level or OSR interpretation. This mode replays no target
and attaches no observer.

Each task retains its raw start and completion stamp tokens. A nominal decimal
stamp difference requires one direct completion, well-formed identity, consistent
thread/process/start ownership and nondecreasing stamps. Missing, malformed,
duplicated and unfinished pairs remain unresolved. The three fractional decimal
places printed by the pinned JVM are not nanosecond endpoint accuracy. Equal
printed stamps do not establish zero elapsed work. Quantization, floating-point
formatting, clock-origin/provider assumptions and independent event alignment
remain outside this nominal difference.

The source boundary is inside `CompileTaskWrapper`: assignment precedes the
start stamp, while task/env clearing, completion notification and recycling follow
the done stamp. Stale-task cleanup, disabled compilation and breakpoint rejection
can also produce task pairs without invoking a compiler. A logged pair therefore
does not establish compiler-body or whole-invocation elapsed time. Raw `nmsize`
remains content-size metadata, global `nmethod` size remains `CodeBlob::size()`,
and JFR task code size remains the separate `nmethod::total_size()` section sum.
No XML file length, failed/default zero or missing publication supplies code size
or zero compilation cost.

The input log is bounded to 16 MiB, the inherited parser to one million elements
and the derived report to 64 MiB. Oversized derived output yields a negative
report with input hashes while retaining the original archive and extracted XML.
Raw output is `live-argument-review.json`; the profile retains its common output,
error and exit files and the complete four-file reader import closure. Successful
parsing and valid logged pairs supply no full-root census, global loss, native
lifetime, compiler elapsed-time, code-size or savings proof. Full ordinary/private
phases, callers/helpers/synthetics, queued/failed/unfinished/OSR/replaced work and
source exclusions require their own complete measurement and control evidence.

With `review=return-sizes`, the same saved-archive review also pairs recorded
`total-size` entries and returns by thread and observed call nesting. The complete
original and complementary log reviews remain in the output. A size comparison
requires one publication callback per broker invocation and one size call inside
that completed callback and public compilation pair, matching broker-task and
nmethod receiver metadata, a unique independently
matched successful JFR compilation, and the original read, sequence-delivery and
owned-counter guards. Ambiguous calls are rejected before comparing their values.
The captured low 32 return bits must agree with the recorded unsigned value and
represent a supported nonnegative source `int`, then equal JFR's task code size.
High register bits are retained without assigning them source `int` meaning.

This comparison concerns the section sum returned by `nmethod::total_size()` and
cached for JFR publication. Allocation size, immutable-data size and XML `nmsize`
do not replace that value. Missing, outside-publication, duplicate, failed and
unfinished calls remain in the report. A matching value supplies complementary
return metadata; it does not establish the full calling convention, complete
code-size/root coverage, clocks or compiler savings, and never changes original
argument-binding or capture-wide acceptance flags. The review performs no new
field reads, observer attachment or captured JVM replay.

### Saved JVM cohort task logs

`com.eignex.klause.bench.profile.JvmTaskLogCohortReview` accepts
`manifest=<https URL>`, `manifest-sha256=<hash>` and `archive=<https URL>`.
Use public URLs without user information, query or fragment. The profile streams
the manifest and archive with separate 16 MiB and 2 GiB download bounds. The
manifest must match its supplied hash; the child independently rechecks the
complete archive size and SHA-256 against that manifest. The archive occupies
owned temporary transport staging, removed after the child finishes; its original
public URL, full byte identity and raw manifest remain in the capture.

The reader requires one original `profileN/cases/` prefix and enumerates every
archived case directory from the complete file inventory. It retains cases with
no log and reviews every literal `hotspot.xml` member, including nested and
outside-case logs. Multiple logs keep separate identities and case references;
outside-case ownership remains unknown. No compiler family, method, engine or
outcome selects a subset. Every archive member is checked for safe regular-file
membership, size and SHA-256 before any log is interpreted, including members
that are not extracted. Linked, duplicate, unexpected and missing members decline
the archive binding. Duplicate manifest JSON keys are rejected.
The archive download bound applies to compressed transport bytes. Individual
member identities require nonnegative integer sizes; the expanded cohort bound
is checked against each tar member before reading its payload. A large unselected
report is hashed incrementally rather than extracted or interpreted, so its
expanded size need not fit the compressed archive download allowance.

Complete raw logs are retained losslessly as `raw-logs/log-NNNNNN.xml.gz` and
per-log reports as `log-reviews/log-NNNNNN.json.gz`. `cohort-review.json` maps
original members and case directories to those files, including their compressed
identities and every missing, malformed, oversized or unresolved log. The cohort
reader streams XML into the existing metadata schema without retaining its full
tree. It preserves document ordinals, thread/task ownership, direct completions,
queues, publications, fragments, failures and unknown fields. Its metadata enters
the same decimal-stamp and proof-denial checks as the bounded single-log reader;
that reader retains its separate input/output bounds. A successful inventory does
not establish the expected study's full case universe, runtime source binding,
full compiler roots, exact elapsed time or code size.

Resource bounds are 100,000 original members, 4,096 archived case directories,
8,192 logs, 16 GiB expanded member bytes, 128 MiB retained and interpreted raw
bytes per log, and 512 MiB stored compressed case evidence. The compressed budget
leaves room for archive framing and harness files under the lab's 1 GiB case
transfer limit. Oversized or missing transfer files remain explicit failures;
they cannot establish complete archived-data validation. Streaming XML is bounded
to 16 million elements, one million retained metadata rows and depth 1,024.
UTF-8 decoding and the complete uncompressed size/SHA-256 are checked across all
chunks; declarations and entities are rejected. Derived reports stream directly
into gzip under a 512 MiB uncompressed per-log bound, without an expanded report
file or whole-report JSON string. Each complete gzip is round-trip authenticated;
an over-budget report is replaced by an explicit negative with raw provenance.
The cohort index is bounded to 64 MiB. A raw log above its retention bound remains
identified in its complete original archive, without a truncated replacement.
Per-log failures keep negative reports and raw provenance; cohort authentication
or storage failure prevents a complete review claim. No omitted or oversized input
becomes zero work, and valid logs do not rescue missing cases or rejected logs.

The profile retains its input origins, raw manifest, archive digest, six-file
reader import closure, output/error/exit files and complete owned capture. Its
child allowance is 2,400 seconds. It performs no captured target/JVM/solver replay,
observer attachment or field read.
Full ordinary/private phases, callers/helpers/synthetics, queued, failed,
unfinished, OSR, replaced and source-excluded work, source outcomes and independent
runtime/allocation/RNG/work controls require separate evidence. Nominal printed
stamp differences remain distinct from compiler elapsed-time and code-size proof.

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

### Saved JVM compilation census

`com.eignex.klause.bench.profile.JvmCompilationCohortReview` accepts `manifest=`,
`manifest-sha256=` and `archive=` HTTPS inputs. It authenticates the complete
original archive and every member before decoding any recording. It extracts
case JFR/XML and bounded original metadata into private temporary storage, hashes
unselected members incrementally and removes the staging directory on exit.
Large derived reports are authenticated without extraction or interpretation.
No captured target JVM, solver or native probe is invoked.

The observer JDK's `jfr print --json` exports Compilation, CompilerInlining,
CompilerPhase, CompilationFailure, DataLoss, ActiveSetting and JVMInformation
events. The complete exported JSON bytes are retained in deterministic gzip;
event indices refer to that export, separately within each original recording.
Unknown event fields remain in the export. The parser streams UTF-8 and individual
JSON records, rejects duplicate keys, malformed/truncated envelopes and trailing
bytes, and never loads a complete export or derived report as a string.

The census retains C1, C2 and unknown tiers, ordinary/OSR, successful/failed and
replacement root events. Exact textual JFR durations become integer nanoseconds;
numeric or unknown units remain unavailable. Reported successful `codeSize`
stays distinct from XML content size, instruction size, file bytes and CPU.
Missing quantities and empty root sets are unavailable, rather than zero.
Direct methods and successful inlining references retain complete root costs.
Each root is counted once within a family; shared family totals are nonadditive.
Ambiguous compile IDs, incomplete method identities, failed inline attempts and
unresolved successful references remain explicit. Full XML metadata preserves
all-tier queues, started/installed roots, failures, replacements and parse errors.

Every archived case directory is retained. The original `cases.json`, individual
measurement and recording/XML identities must agree for case decoding completion.
Missing, extra, nested or ambiguous recordings and malformed XML remain negative.
Observer bench/JDK files are hash-bound before review and checked for changes
afterwards, separately from the original CLI/runtime/source/worker context.
Family declarations and observed ordinary-phase roots are reported;
their correspondence to complete current source phases requires independent
review. Original source-check outputs and exclusions are preserved without an
outcome upgrade. Decoding completion supplies no complete lifetime, loss-free,
source-phase, current-runtime or saving proof. Instrumented JFR durations do not
replace independent runtime, allocation, quality, RNG/work or cold-run controls.

Saved `class-load.log` files and numbered rotations are authenticated with every
other archive member. All class-load observations and unparsed lines are retained
separately, together with each original installed CLI manifest and the available
harness provenance, requested options and study plan. A file URI can identify a
recorded jar candidate only by its exact original distribution path and SHA-256;
matching jar basenames do not establish an association. Shared archive sources,
generated classes, missing manifests and conflicting observations remain unbound.
Rotated or multiple logs do not supply a single complete origin stream.
Class logs declared omitted by the primary source-filter inventory also prevent
complete origin attribution. An append-only companion can restore those original
bytes through `class-origins-manifest=`, `class-origins-manifest-sha256=` and
`class-origins-archive=`. All three are required together. Its catalog must name
the exact `primaryArchive`; only omitted class-log paths with identical original
sizes and hashes may be added. Existing members cannot be replaced. Every
companion member is authenticated, including unselected provenance, before log
parsing. The primary archive remains immutable, and each restored log retains its
own archive identity. Multiple process lifetimes or rotations are not combined.

Every compilation root and inline caller/callee keeps a reference to its class
association table, including missing or malformed method identities. Repeated
class observations remain distinct. A recorded jar candidate does not establish
class-loader identity, actual loaded byte digests, complete source phases or
compiler savings. These observations do not change JFR/XML case completion,
reported costs, eligibility or source/outcome proof flags.

Bounds are 16 MiB for the manifest, 2 GiB compressed/16 GiB expanded for the
archive, 8 GiB selected scratch inputs, 128 MiB per recording/XML/class log and 32 MiB per
original metadata file. Each export allows 8 GiB of JSON, 8 MiB per event,
four million events and one million retained root/inlining rows. A JFR child has
300 seconds and 1 MiB of stderr; the enclosing review has 2400 seconds and kills
owned descendants on timeout. Derived reports allow 512 MiB expanded each;
the whole compressed output stays below 512 MiB, leaving transfer headroom.
Partial export, tool failure and resource declines retain explicit negatives and
recoverable original input identities. Publication still requires source filtering
and complete byte verification of every retained gzip payload. Each class log
allows 250,000 complete UTF-8 lines with at most 64 KiB per line; invalid UTF-8
or a bound decline cannot authorize a parsed prefix.
The primary and optional companion share the compressed archive, expanded member,
member-count and selected-scratch bounds; each manifest keeps its 16 MiB limit.

### Saved JVM census cost comparisons

`com.eignex.klause.bench.profile.JvmCompilationCohortCosts` accepts the same three
manifest/archive arguments as the saved-census reader. Its input is a published
complete saved-census archive. It authenticates every member before interpreting
the cohort index, original case metadata and complete per-recording census files.
It performs no original JVM or solver replay and does not run `jfr print`.

Optional `mapping=<https-url>` and `mapping-sha256=<sha256>` arguments supply a
separate phase declaration. Both are required together. The bounded JSON object
uses `schema=1`, `inputCensusArchive` (size and SHA-256), `originalFamiliesIdentity`
and `originalStudyBuildsIdentity` (uncompressed original sizes and SHA-256 values),
`sourceHeads` (control and candidate Git heads) and `families`. Original metadata
gzip identities and both recorded expected/actual heads must match. The family
universe and every original supporting method selector must be retained. Each
family declares `methods`, `ordinaryPhase` and `ordinaryPhaseAll`; required phases
must be declared for their arm. Empty declarations retain their exclusions.
An ordinary phase may name a method as `holder.method` or include its complete
JVM descriptor, such as `holder.method(I)V`. Name-only phases match any overload;
descriptor phases require that exact parameter and return descriptor. Both forms
accept direct successful non-OSR compilations or resolved successful inlining into
a successful non-OSR root. Missing overloads remain missing required phases.

The observer authenticates the complete retained XML metadata and reforms mapped
families from all saved compilation and inlining rows, including successful
outside roots. Missing or incomplete XML stays excluded; ambiguous IDs, queued or
unmeasured roots, OSR, failures and conflicts retain the census rules. The report
keeps the original comparisons unchanged and adds `phaseMapping` observations,
pairs and summaries. Mapped rows reference the original family census by case,
family and authenticated report identity, avoiding another copy of its root lists.
Plain summaries include `mappedComparisonSummaries` alongside
the original summaries. The mapping input and its hash remain captured. Recorded
head equality establishes metadata scope only; it does not prove loaded classes,
descriptors, synthetic ownership or exhaustive source-phase coverage.

Report format version 2 stores each original or mapped family observation once.
Every pair arm contains `observationIndex` references into its sibling
`familyCaseObservations` array, preserving duplicate and unexpected arms. Original
family censuses retain their producer representation. Mapped XML references use
`unmeasuredAllTierXmlSnapshotIndicesByCollection` and
`conflictingXmlSnapshotIndicesByCollection`: each maps `queued`, `tasks` or
`nmethods` to every corresponding source index in order. Empty maps mean no
references; repeated indices remain repeated. Expanding each collection/index
pair recovers the complete reference list. Roots, phases, metrics and exclusions
are unchanged. These representations avoid duplicate root lists and repeated XML
collection labels without increasing the output bounds or selecting away records.

`compilation-cost-comparison.json.gz` retains every original family/case row and
pair, original source-check status, whole recording/tier costs and report recovery
identities. Pair keys include engine, input index, repetition, mode and recipe.
`compilation-cost-summary.json` and the child stdout retain every family/engine/
mode/recipe summary and excluded pair context. Summary maxima are sums of paired
per-recording maxima, with a retained difference range; they are not cohort maxima.
The summary file and its stdout mirror use compact JSON under the complete-output
byte bound.
`familyObservationSummaries` and `mappedFamilyObservationSummaries` also retain
the already computed all-tier and C2 scalar quantities for every family/recording,
including excluded contexts. Their source/phase exclusions and missing quantities
stay explicit. These descriptive observations do not qualify a comparison or
establish exhaustive source work or a current source change's benefit.
`recordingObservationSummaries` retains every recording's all-class and tier
scalars, original context keys, census identity and `savedJvmContext`. The latter
authenticates both compressed and expanded identities of the referenced original
`events-*.json.gz`, then streams that complete saved export without `jfr print`.
Every JVMInformation event retains its original event index and complete values,
including unknown fields, commands, arguments, process identity and start time
when recorded. Missing exports, absent events, repeated or conflicting contexts,
incomplete original censuses and mismatched event counts remain explicit.
No context is selected from multiple events and no target process role is inferred.
Unreferenced saved exports remain named in `unreferencedSavedExportPaths`.
Context availability does not change original or mapped pair eligibility.
`methodRecordingObservations` retains every direct root method, inline callee and caller,
including unknown identities and methods seen only in failed inline attempts.
Each row references its original recording, direct roots, successful outside
inlining roots, all inline attempts, unresolved successful references and root
errors. Inclusive costs deduplicate a shared root within a method, preserving all
tiers, ordinary/OSR, failed and replacement events; C2 values remain separate.
Successful-inlining roots are shared and nonadditive across methods.
Caller references alone do not authorize an inclusive root association; methods
seen only as callers retain those references with unavailable cost quantities.
`observedMethodSummaries`, also retained in the plain summary, groups every
observed exact method identity by arm, engine, mode and recipe across recorded
input indexes and repetitions. It references every method observation and names
recordings without that method observation. Those absent contexts are unavailable
and do not become zero-cost samples. Each metric sums the observed recording
values only when every such value is available; the method maximum is the largest
reported root duration across those recordings. This differs from the sum of
per-recording maxima in the family comparison summaries.
Recording summaries preserve original source outcome, source-hash status,
invocation exit, case and cohort completion alongside the original JVM context.
These descriptive method summaries do not change family eligibility, select a
process role, establish exhaustive source work or qualify a compiler saving.
Missing, duplicate or unexpected arms, mismatched problem identities, invalid or
unknown original source outcomes, incomplete cohorts, missing declared ordinary
phases, unresolved roots, XML gaps, data-loss events and ambiguous IDs remain
excluded. Failed roots and missing metrics are retained; absent costs are not zero.
Available differences and exact ratio operands describe observed all-tier JFR
quantities. C2 values stay separate, and shared full-root family costs are not
additive. No family, adverse observation or excluded pair is selected away.

Declared-phase eligibility does not prove exhaustive source phases or a current
source change's benefit. Original runtime/source outcomes retain their own scope;
current runtime, allocation, RNG/work, independent and cold controls are separate.
All full-root, elapsed-time, code-size and saving proof flags remain false.
Resource bounds are 2 GiB compressed archives, 16 GiB complete member expansion,
512 MiB selected compressed metadata and saved exports, 512 MiB per expanded census JSON and
512 MiB complete compressed output. Transport staging is removed after the child
finishes. Optional mapping inputs are bounded to 16 MiB. The observer retains its
installed build/JDK identity, exact thirteen-file
Python closure, original input identities and actual child streams/exit.
Saved exports keep the census reader's 8 GiB expanded, 8 MiB per event and four
million event bounds. Each recording allows at most 4096 JVMInformation events
and 16 MiB of complete context JSON; exceeding a bound declines the complete
context review and cannot authorize a parsed prefix. Existing 512 MiB report and
64 MiB summary limits also apply to the retained contexts.
The complete method observation table allows 250,000 rows across the cohort;
exceeding the bound declines the review without selecting a method prefix or
raising the existing report, summary or output limits.

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

### Objective representation probe

`com.eignex.klause.bench.profile.ObjectiveRepresentationProbe` measures the public
affine and exact quadratic entries on the same admitted polynomial real source.
Pass `source=<SMT file or directory>` and optionally `engines=BACKTRACK,FIXED,LOCAL_SEARCH,ALNS,MIXED`,
`work=200000`, `seed=17`, `moves=1`, `timeout=25000`, `out=<directory>` and
`checker=none|existing|provision` (default `none`), with `processors=1` by default.
If the launching JVM sees a different processor count, the probe runs in a child
JVM with `-XX:ActiveProcessorCount` and its own `objective-probe.jfr`; the lab's
parent recording covers the wrapper. Settings record the actual execution JVM.
`schema=<JSON SchemaDef>` selects the compiled/schema path for the same original
polynomial real sources. Declaration names must agree; real coordinates use public
compiled source IDs and float objective handles. Finite-grid floats, integer schema
declarations absent from the original source and aliased named real coordinates
decline. The captured schema bytes
and hash accompany exact named original-source results; compiler auxiliaries are
excluded from the original witness map.
The source objective must be affine, with integer Boolean weights/constant fitting
Long and real coefficients exactly representable in binary64. Other objectives
decline rather than acquire rounded coefficients. Each execution gets fresh state,
the original direction and the same controls. Encoding order alternates across engines.
Source bytes, exact rational/algebraic results, native work, heuristic moves, elapsed
execution time, settings and installed-build/runtime hashes are retained under `out`
or `$OUT`. Parsing and provenance capture are outside the execution timer.
Nonlinear integer source metadata is supported on the compiled/schema path, with
separate original Boolean, integer and real namespaces. No source domains are
inferred from a schema box; independent result checking uses original constraints.

`conditions=<JSON file>` supplies caller pins keyed by original source SHA-256.
Each entry contains optional `bools` and `ints` maps from original names to exact
Boolean/Long values. Missing source entries, undeclared names and other condition
kinds decline. The probe retains condition bytes/hash and the applied named pins.
One compiled request is reused across source cases; each execution captures its own
supplied objective, direction and conditions. The
[objective-scopes-v1 fixtures](smoke-corpus/objective-scopes-v1/manifest.json)
change objectives and pins over finite Int domains, AllDifferent and fixed nonlinear
real coordinates. Boolean, integer and real declaration zero remain distinct.

Run the committed main through a lab profile job on a trusted pushed branch:

```sh
deploy/lab profile com.eignex.klause.bench.profile.ObjectiveRepresentationProbe <branch> \
  "compare objective representations on original sources" aws 1800 \
  source=klause-bench/smoke-corpus/objective-affine-v1 checker=provision
```

Add `schema=klause-bench/smoke-corpus/objective-affine-v1/square.schema.json` to run
the same inputs through schema compilation and its named float objective API. Its
declared `0<=y<=1` follows from the original source `x*x=y`, `-1<=x<=1`.

Use `source=klause-bench/smoke-corpus/objective-scopes-v1`,
`schema=klause-bench/smoke-corpus/objective-scopes-v1/scope.schema.json` and
`conditions=klause-bench/smoke-corpus/objective-scopes-v1/conditions.json` for the
40-record changed-scope comparison. All coordinates are fixed by the source scope,
so a zero heuristic move count is legitimate; exact witness, condition, result-role
and bound checks remain required.

`capture=required` on the compiled/schema path recaptures actual producer results
through the original IR affine or quadratic capture API. Native completions must
retain their typed derivations and reject a copied affine objective or weaker caller
conditions. Records keep the runtime capture and replay checks separate from the
independent original-source mathematics. Unsupported derivation coverage remains
explicitly unavailable; these records do not export engine proofs. Capture checking
runs outside the solver execution timer.

The [precision fixtures](smoke-corpus/objective-affine-v1/manifest.json) distinguish
adjacent exact objective values sharing a binary64 projection. After fetching the
probe output, check its original sources independently with the pinned source-check
requirements and [verify_objective_probe.py](tools/verify_objective_probe.py):

```sh
python3 klause-bench/tools/verify_objective_probe.py /path/to/probe/out \
  --require-incumbent --output /path/to/source-checks.json
```

`checker=existing` runs the independent verifier with the host's `python3`.
`checker=provision` installs Linux `python3-venv` through sudo/apt and the pinned
requirements into an output-local environment before running the same verifier.
Dependency installation and verification are outside the execution timer; install
logs, dependency versions and checker tool hashes accompany the checked records.

The verifier requires both encodings for every selected engine and original source.
It checks exact witnesses, costs, claimed global bounds, strict nonattainment and
refutations. Approach paths, unbounded trajectories and exported proofs are explicitly
unavailable from these records. JFR-instrumented elapsed times are diagnostics, not
an uninstrumented performance comparison. Keep all generated records externally.

### Objective CLI publication probe

`com.eignex.klause.bench.profile.ObjectiveFrontendProbe` runs the installed CLI
on a directory's original source manifest. Pass `source=<directory>`, optional
`out=<directory>`, `work=200000` and `checker=none|provision`. Manifest cases name source files,
hashes, family/encoding identities and selected CLI engines. The probe retains
original bytes, stdout/stderr/exit, launcher/runtime provenance and per-child JFR.
Each child uses `-p 1`, seed 17, the recorded native work cap, one LS move/ALNS iteration and
a 25-second process allowance. Its actual JFR JVM arguments must retain
`ActiveProcessorCount=1`. Timings include process startup, solving and delivery;
JFR collection affects them, so they are diagnostics rather than performance claims.

The [objective-cli-v1 manifest](smoke-corpus/objective-cli-v1/manifest.json) fixes
76 outcomes: mixed product and rational objectives across all five engines, plus
algebraic, strict-limit, unbounded and infeasible controls through CP, FIXED and
MIXED.
Paired affine and zero-square objectives preserve original constraints and direction.

```sh
deploy/lab profile com.eignex.klause.bench.profile.ObjectiveFrontendProbe <branch> \
  "check original objective publication through the CLI" aws 1800 \
  source=klause-bench/smoke-corpus/objective-cli-v1 checker=provision
```

[verify_objective_frontend.py](tools/verify_objective_frontend.py) checks the complete
manifest matrix, original hashes, exact witnesses/costs, source bound direction,
global inequalities and strictness. Multiple accepted models use the shared stream
checker for every source witness, exact cost and strict original-direction improvement;
terminal checks use the final published point. Its one-variable affine controls independently
check source approach paths and witness-anchored improving rays. Heuristics retain
BestFound status; MIXED may finish before a heuristic move. Actual engine proof
export is not captured. Provisioning uses pinned source-check requirements in an
output-local environment and retains checker/package/runtime identities before
disposing that environment. Keep generated outputs and recordings externally.

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

### CP preparation probe

[scripts/cp-preparation](scripts/cp-preparation/) measures retained general-CP projection,
state and root batches, then the full assumption/LP/search installation. Parsing and
baking are outside measurement. Use `synthetic` for independent integer rows with
512 assumption pins, or an uncompressed XCSP3 optimization input. A zero solve-wide
decision allowance prevents decisions; a token fires at installation handoff before
the first search dispatch. LP solves and work are reported separately and must stay
zero throughout this construction measurement. Round zero warms
the JVM; later rounds repeat the same construction. Root-domain hashes, propagation
work and charged work must match before comparing timings or allocation totals.

```sh
./gradlew -I klause-bench/scripts/cp-preparation/cp-preparation.init.gradle \
  :klause:cpPreparationProbe --args="synthetic 8192 4"
```

`-PpreparationProbeCli=/absolute/path/to/klause-cli-jvm` selects a pinned installed
runtime; rebuild it with `:klause-cli:installJvmDist` first and retain source, probe,
input, installed-build and runtime hashes. Per-batch and one-millisecond segment
measurements include polling and dispatch. Primitive allocations, one propagator
operation and payload copies can overshoot a cooperative boundary. These timings
characterize that residual latency; they do not establish a hard wall-clock bound.
The probe also records uncapped installation for comparison and exact JVM-thread
allocation bytes. Pair it with uninstrumented source-checked whole-CLI controls;
construction completion and root hashes alone do not certify source results.

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

### Definition probe

[scripts/definition-probes](scripts/definition-probes/) measures repeated complete
coordinate/cone grading and occasional committed moves on a fixed seeded move list.
The built-in synthetic fixture exercises selected Element cells and a product chain;
an uncompressed XCSP3 source path exercises its inferred definitions. Setup and per-move
restoration checks precede timing. Round zero warms up; rounds one–six repeat the work.

```sh
./gradlew -I klause-bench/scripts/definition-probes/definition-probes.init.gradle \
  :klause:definitionProbe --args="synthetic 2048 3"
```

`-PdefinitionProbeCli=/absolute/path/to/klause-cli-jvm` selects installed classes.
Rebuild pinned distributions with `:klause-cli:installJvmDist`, and retain source,
probe, input and installed-build/runtime hashes. Run alternating paired repetitions on
the same machine with matching JVM options. Match move/probe counts, final assignment,
degrees, cost, weights, activity and RNG digest before comparing thread allocated bytes
and uninstrumented time. Final incremental grades must match a full recompute.
The prepared-model scope excludes parsing, preparation and whole-CLI publication;
pair it with independently source-checked first-solution controls across families
and scheduling settings. Allocation changes alone do not establish complete-solve gains.

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
