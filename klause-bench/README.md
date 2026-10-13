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

`lab files` lists a job's files and `lab cat <id> <path> [tail-bytes]` prints one, such as
`job.log`, `setup.log` or `cases/<index>/record.json`, over HTTP: reading a job needs no SSH.
`lab ssh <id> [instance] [command]` reaches a running AWS job's instance. Measure as a lab
experiment rather than directly on the lab Mac, where a run competes with the lab's cases.
A measurement no experiment expresses, such as timing a component on captured inputs, runs as
a profile job: `lab profile <main> <ref> <description> [aws|lab] [timeout-sec] [arg...]` runs
the class `<main>` (directly in `com.eignex.klause.bench.profile`, committed at `<ref>`) once
under JFR, on AWS by default, and keeps its output and whatever it writes to `$OUT` with the job.
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
  "host": "aws",
  "machines": 1,
  "parallel": 1
}
```

For example, the first argument can be
`{"semantics":"klause-ir-exact-v1","kind":"real-box-square","lower":-2,"upper":3,"coefficient":1,"constant":0,"sense":"MINIMIZE"}`.
Save the specification outside the repository, then use
`LAB_URL=https://lab.eignex.com ./deploy/lab run /absolute/path/to/profile-source-evidence.json`.

The probe saves original bytes and the actual result/capture payload under `$OUT`,
then runs the packaged independent Python fractions checker in a separate process.
`independent-check.json` records checked or unavailable coverage and source, payload
and checker identities. `runtime-provenance.json` records the revision, JDK and
classpath artifact hashes. An unavailable capture cannot count as checked evidence.
This fixture checker is not general proof-file export, a verdict reference comparison
or a performance measurement. Retrieve records with `lab files`, `cat` and `fetch`;
keep generated output outside the repository.

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
| `stats.frontendLoadMs` | Inclusive frontend parse, lowering, routing and solvable construction; excludes process startup |
| `stats.frontendRoutingMs`, `frontendRoutingOutcome` | Measured frontend routing time and outcome; absent when unmeasured; nested within frontend load |
| `stats.solvePipeline`, `declaredTheoryRoute`, `reportedBackend` | Selected CLI solve entry point, pre-preparation theory declaration, and final backend snapshot; distinct stages |
| `stats.nativeCpPropagationWork`, `nativeCpRootPropagationWork`, `nativeCpPropagationMs`, `nativeCpRootPropagationMs` | Existing native fixpoint aggregates when native measurement is evidenced; root is a subset, not open-theory root cost |
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

### CLI stream capture

`com.eignex.klause.bench.profile.CliStreamCapture` runs the installed CLI as a subprocess
and retains original input bytes, build/runtime provenance, command, stdout, stderr,
and a JSONL arrival record with byte offsets and subprocess liveness. Run it through
a lab profile job on AWS. Capture settings precede `--`; the remaining arguments go
directly to the CLI, followed by the preserved input path. Supply `input=<path>` or
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
terminal proof claims.
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
`source-check-fzn.json`. It uses authoritative coordinate comments when supplied and
checks the exact preceding cost against the original solve objective. Its source scope
is `flatzinc-binary64`, as described under source validation below. Interrupted
captures reject verdict markers and terminal `optimizationStatus` comments, including
unattained boundaries; these belong after invocation completion.
`com.eignex.klause.bench.profile.SmtStreamReplay` checks saved SMT output through a
lab profile without invoking the CLI again. Its one argument is a base64 JSON array
of records containing `sourceRevision`, original `source` and `stdout` text, and
their `sourceSha256`/`stdoutSha256`; optional `interrupted` and `minimumWitnesses`
use the stream checker's controls. It verifies byte hashes before checking each
record, preserves the original solver revision separately from checker provenance,
and retains an outcome for every supplied record. Replay does not relabel the
original solve or establish its output timing.

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
array lists, groups, slides and blocks for these checks.

Supported expression trees are evaluated with `BigInteger` and compared with the
pinned evaluator, including its normalized intension tree. Matching cancellation
results remain checkable; any disagreement or unsupported operation leaves checking
`unknown`. Supported expressions include integer arithmetic (except powers),
comparisons and Boolean operators. Hybrid extension tuples, set expressions, powers, expression sums,
logical meta-constraints, flow, channel, element, circuit and indexed extrema
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

### Offline source verifier

`com.eignex.klause.bench.profile.MpsLinearProof` accepts original MPS paths and captures the
ordinary exact-source optimization result, original coordinate mapping, rows and derivation.
It runs [verify_mps_linear_proof.py](tools/verify_mps_linear_proof.py) in a separate Python
process using only standard-library exact fractions. The checker parses original unguarded
linear MPS bytes, checks every captured premise against source rows or integral declarations,
eliminates an exact objective auxiliary, and validates nonnegative dual combinations,
integer branch coverage or an integral improving recession step. Unsupported source features
and missing derivations fail closed. This checks mathematical claims rather than timings.
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
`--route=authority` isolates the public source-authority entry point with cp and one
core. The default `--route=request` exercises ordinary request and portfolio execution;
the output records which entry point produced the claim.
`admission-box.smt2` is a self-authored bounded real source beyond the numerical
authority's column ceiling. With `--work=8` it checks the retained source witness
from feasibility fallback; `--work=1` checks that the authority attempt cannot renew
the containing allowance. Neither control may publish a terminal proof after decline.

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

`bench.profile.SourceRecordCheck` runs this verifier on saved records through an
AWS lab profile without rerunning the solver. Pass `bundle=<https URL>` and
`sha256=<compressed SHA-256>` for a gzip JSON object containing `cases` (the case
array) and `sources` (original source SHA-256 keys with base64 original bytes).
It checks bundle/source identities, provisions the pinned checker dependencies,
and retains checker logs, versions and a checked prefix on failure under `$OUT`.
Original unknown records retain their no-claim outcome; reference agreement does
not independently verify an exported proof.

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
case IDs, 32 KiB per source or model and 48 KiB for the complete encoded request.
These bounds can exclude large original inputs; validation never drops coordinates
or rewrites a source/model to fit. No catalog scan, solver call or reference search
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
checks complete named Int/Real/Bool models against an original polynomial SMT source.
It reconstructs rational coordinates and `root-obj` algebraic coordinates using the
pinned Z3 dependency, pins every source variable, and requires every
original assertion and Boolean query assumption to evaluate exactly to true.
Algebraic coordinates use integer defining polynomials and one-based distinct
ordered real-root indices. Approximate decimal coordinates do not establish exact
irrational equalities. Missing, duplicate, unknown or sort-mismatched coordinates
are rejected. Integer coordinates must be integral and retain arbitrary precision.
Nonpolynomial, optimization and incremental sources decline.

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
64 outcomes: mixed product and rational objectives across all five engines, plus
algebraic, strict-limit, unbounded and infeasible controls through CP/FIXED.
Paired affine and zero-square objectives preserve original constraints and direction.

```sh
deploy/lab profile com.eignex.klause.bench.profile.ObjectiveFrontendProbe <branch> \
  "check original objective publication through the CLI" aws 1800 \
  source=klause-bench/smoke-corpus/objective-cli-v1 checker=provision
```

[verify_objective_frontend.py](tools/verify_objective_frontend.py) checks the complete
manifest matrix, original hashes, exact witnesses/costs, source bound direction,
global inequalities and strictness. Its one-variable affine controls independently
check source approach paths and witness-anchored improving rays. Heuristics retain
BestFound status; MIXED may finish before a heuristic move. Actual engine proof
export is not captured. Provisioning uses pinned source-check requirements in an
output-local environment and retains checker/package/runtime identities before
disposing that environment. Keep generated outputs and recordings externally.

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
