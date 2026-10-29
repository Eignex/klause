# klause-bench

JVM problem catalog and single-case runner for
[eignex-lab](https://github.com/Eignex/eignex-lab). Use the lab for experiments,
revision comparisons, reference solvers, witness checks, schedules and profiling.

## Private rebuild qualification capture

Enable `param.root-simplification=true` and
`param.root-rebuild-capture-visits=8192` with explicit containing
`param.inprocessing-work` and `param.inprocessing-root-work` grants. The opt-in scan
runs after stable-ID simplification at eligible scheduled roots and shares I2's work,
cooldown and debt controls. Keep capture disabled as an equal-total-allowance control.
`nativeInprocessRootRebuildComplete` counts full views; `Partial` and `Unsupported`
withhold conclusions. `ProgressPins` excludes the original baked-root facts.
`StableRemoved`/`StableShortened` count changes already handled without elimination;
`NewCandidates` counts unfixed columns newly meeting the non-growing resolvent-pair
proxy described in [the contract](../docs/07-presolve.md#private-worker-qualification).
It does not build resolvents or qualify objectives, arithmetic passes or learned imports.
Aggregated views can revisit the same facts. `ScratchBytes` is cumulative modeled
array allocation, not a peak; a view uses at most `18 * floor(visits / 10)` primitive
bytes and retains no capture buffers. `CaptureWork` is included in root `Work` and
worker work, not additional work to sum. Tiny grants may leave every scan partial.

`deploy/lab cases ID --json | klause-bench/scripts/capture-private-rebuild.sh`
extracts the source/build/resource identities, original outcomes/check receipts,
existing P3 preparation and I2 lifecycle metrics, and a full-cost ledger. Keep the
complete lab record and physical execution metadata beside the extracted view.
No runtime rebuild is performed by capture. Unmeasured stages stay `null`, including
reconstruction/source checks, lost warm state, import/publication and retirement;
an absent consuming-worker count or memory peak is not zero. A replacement producer
can supply `record.privateRebuildLifecycle` with disjoint `stageNs` in the named
stages, modeled simultaneous owned/protected primitive bytes and reference slots,
released obsolete generations and actual consuming workers. `totalNs` is withheld
until all stages are observed; it excludes ordinary solve time, which remains
separate. Do not sum overlapping preparation/inprocessing counters or sampled peaks.
Whole-job active time must additionally include failed preparation, checking and
artifact/retirement work from the lab job record.

Qualification compares the same hashed source, seed, runtime, physical allocation
and containing allowances with stable-ID work alone. A runtime rebuild requires
the [private generation contract](../docs/07-presolve.md#private-worker-qualification),
bounded ownership/reclamation, compatible consuming workers and a benefit after
all lifecycle costs. A lack of new candidates on a complete propositional control
supports only a bounded no-go for that control. Unsupported/partial views need an
applicability or grant diagnosis and cannot support a general no-go.

## Build and run

Run from the repository root with JDK 25 or newer:

```sh
./gradlew :klause-cli:installJvmDist :klause-bench:installDist
cd klause-bench
./build/install/klause-bench/bin/klause-bench list
./build/install/klause-bench/bin/klause-bench select suite=dimacs-core files=true features=true
./build/install/klause-bench/bin/klause-bench solve-one suite=dimacs-core problem=php4 timeout=1000 out=/tmp/php4
```

Rebuild `:klause-cli:installJvmDist` after CLI or solver changes: `solve-one` runs
that installed CLI as a subprocess. `:klause-cli:installDist` does not refresh it.
The installed bench launcher uses `klause-bench` as its working directory.
For source-tree commands, use
`./gradlew :klause-bench:bench --args="select suite=dimacs-core"` from the repository
root; `-Dklause.*` properties are forwarded to the bench JVM.

## Select and solve

`list [suite]` lists suites or their problems. `select` emits JSON lines containing
the `suite` and `problem` identities accepted by `solve-one`.
`files=true` adds resolved model/data/include paths; `features=true` adds structural
classification. Fetched suites download their corpus during resolution.

| Selection filter | Purpose |
|---|---|
| `suite=a,b` | Restrict suites; `core` expands to the in-process core |
| `set=a,b` | Select named sets from [sets/](sets/); `suite=` intersects membership |
| `kind=cop\|csp`, `category=SAT,UNSAT,CSP,OPTIMIZATION,…` | Filter objective kind or category |
| `tag=a,b`, `name=<glob>[,…]` | Filter tags or names; name patterns are ORed |
| `per-family=N`, `max=N`, `seed=N` | Cap and deterministically sample selection |
| `balance=format` | Distribute the overall cap across formats |

Named sets contain one `<suite>/<problem>` per line; `@name` includes another set
and `#` starts a comment. They bypass default suite family caps.

`solve-one suite=ID problem=NAME out=DIR` accepts these controls:

| Option | Purpose |
|---|---|
| `timeout=MS`, `solver-seed=N`, `processors=N` | Solver deadline, seed and processor count |
| `engine=fixed\|cp\|mixed\|ls\|alns` | CLI engine; omitted uses the CLI default |
| `lp=off\|conservative\|default\|aggressive[±id…]` | LP ceiling and technique overrides |
| `presolve=off\|conservative\|default\|aggressive[,±pass…]` | Presolve emphasis and pass overrides |
| `exact=true\|false`, `param=key=value` | Exact mode and repeatable CLI parameters |
| `complete-assignment=true\|false` | Opt-in bounded complete integer/Boolean FlatZinc coordinates as witness comments |

Each run writes `<problem>.out` and `<problem>.json` under `out`, including failed
loads and solves. Records retain solver reports, statistics, source hashes,
installed-build identity and available witnesses. Inspect the JSON record even
when the bench process exits successfully. Independent source validation runs in
the lab; the bench records `validationPolicy=reported-result-v1`.

Subprocess stderr is drained in fixed-size chunks. Records retain at most 16 Ki
characters of normalized stderr, with `stderrComplete` true only for an untruncated
stream that reached EOF without a forced kill or drain failure. Refusal and crash
classification scans the whole stream incrementally; diagnostic summaries have a
separate 2,000-character bound and report `diagnosticsTruncated` when exceeded.

When capture is configured, `cli.stderr` preserves raw bytes independently of the
record prefix. `stderrCapture` in the record and `cli.stderr.json` beside the artifact
report `bytesRead`, `eof`, `recordTruncated`, `diagnosticsTruncated`, `processKilled`,
`drainTimedOut`, `artifactRequested`, `artifactComplete` and any `readFailure`,
`artifactFailure` or `metadataFailure`. Artifact write failures preserve draining and
classification. Cleanup waits at most two seconds for the stderr reader; a timed-out
reader gets asynchronous close and incomplete metadata. Failed invocations retain
their exit status, partial output and capture metadata in error records. Trace
comparisons require `artifactComplete=true` in the completion marker, rather than
the record prefix; killed or failed captures cannot establish full-trace equality.
Complete artifact capture certifies emitted bytes, not successful solving or logical
termination. Check the recorded process exit and producer termination events too.

`complete-assignment=true` also works in lab request parameters and stays off by
default. The final witness preserves its framed comments after model reconstruction;
normal model output stays unchanged. Retain `report/model.fzn`, `model.ozn`, the
native output and compiler identity with the witness. A missing or unavailable
block cannot certify hidden coordinates; capture itself is not an independent check.

## Run through eignex-lab

The [lab repository](https://github.com/Eignex/eignex-lab) owns submission, execution
and artifact collection. The adapter manifest is [lab/bench.json](lab/bench.json).
Its build tasks install the CLI, bench and pinned MiniZinc runtime:

```sh
./gradlew :klause-cli:installJvmDist :klause-bench:installDist :klause-bench:prepareLabRuntime
```

The adapter supports macOS ARM64/x86_64 and Linux x86_64 with JDK 25.
Preparation stages the checksum-pinned MiniZinc 2.10.1 compiler for its host;
prepared cases retain that host's platform and architecture requirements.
Resource recordings require GNU time (`gtime` from Homebrew's `gnu-time` package on macOS).
Lab source builds disable JVM incremental-compilation indexes because each build
starts from a fresh source tree and the lab reuses complete verified distributions.
Developer and CI builds retain their incremental-compilation settings.
The status sweep and `klause-reference*` schedules use `tags: ["local"]` to stay on the Mac.
Compiler tree identity is shared within one plan; each case execution verifies its pinned tree again.
Include trees are scanned when each workload is planned.

Corpus files with names outside the lab's portable path contract are copied into
`klause-bench/build/lab-inputs` under their content hash. Selection identities and
input bytes stay fixed; private workspace cleanup removes these staged files.
Report filenames escape nonportable characters; records retain original problem identities.
A minimal lab request is:

```json
{
  "repository": "Eignex/klause",
  "base": {"ref": "COMMIT_SHA"},
  "bench": {
    "manifest": "klause-bench/lab/bench.json",
    "request": {"parameters": {
      "suite": "dimacs-core", "name": "php4", "timeout-ms": "1000"
    }}
  }
}
```

Lab parameters use `timeout-ms`; direct `solve-one` uses `timeout`.
`engines`, `solver-seeds` and `repeats` expand cases. Use `param.NAME` for CLI
parameters, `bench.sourceValidation` for independent source checks and
`bench.runTimeoutSec` for the whole job's active-time limit.
Use `param.root-simplification=true`, `param.root-simplify-batch=N`,
`param.root-probe-batch=N` and `param.root-probe-work=N` with the existing
`param.inprocessing-cadence=N` for opt-in stable-ID root controls. Zero probe
batch disables probing. Keep the unchanged SAT preset and general backtrack controls
in comparisons.

`param.inprocessing-work=N` contains each scheduled slice; the `inprocessing-root-work`,
`inprocessing-probe-work`, `inprocessing-subsume-work` and `inprocessing-vivify-work`
parameters separately cap its classes in propagation-work units. Zero withholds work.
`param.inprocessing-interval=N` and `param.inprocessing-growth=N` request work at
natural roots; `param.inprocessing-cooldown=N` requires non-inprocessing work after
an attempt, including restart requests. These triggers default to zero/off and
the additional ceilings default to unlimited. Record class diagnostics alongside
the containing propagation work and learned lifecycle; their work subsets and S4
family credits must not be summed as independent benefit.

Inside `bench.request.parameters`, a CLI setting is `param.NAME`. A lab `configs`
override targets an adapter parameter with its own `param.` prefix, so overriding
that setting uses `param.param.NAME`. Retain the actual CLI command as the consumed
configuration; a problem-level case identity can be shared by several arms.

Profile mains live in [bench/profile/](src/main/kotlin/com/eignex/klause/bench/profile/)
and run as lab profile jobs; consult the selected main for its arguments and
retain its output through `$OUT`.

Profile reflection reads the session's nullable retained propagation owner through
`LivePropagationState`; live-only probes fail explicitly after release, and residual
inspection declines a released session. Captures retain bounded diagnostic traces
and lifetime totals without retaining the propagation owner after retirement.

For repeatable finite portfolio diagnostics, use one processor, a fixed solver seed,
`param.portfolio-work=N` and explicit `param.slice-work`, `param.slice-max-work` and
`param.slice-growth` settings. The containing timeout is an emergency stop. Keep it
large enough for the work limit to finish, then compare the `portfolio ` event lines
in retained CLI stderr across repeats. The bench enables verbose output for this
option. Use `presolve=off` or freeze prepared inputs separately: this scheduler
control does not replace source preparation policy. Retain source hashes, effective parameters and installed build/runtime
provenance with the traces. Compare logical events separately from physical timing,
and report an emergency-interrupted last segment as partial. Default scheduling,
parallel deterministic batches and throughput qualification remain separate controls.

[9140](https://lab.eignex.com/jobs/9140) repeats `php4`, `random3sat-50-200` and
`sporttournament06` three times at seed 17 on `75aaf8357d`, with one Linux/amd64
processor, matching installed-build/runtime hashes, presolve and LP off, 2,000 work
units and a ten-second emergency deadline. Each source reproduces its complete
logical trace. Two-unit slices repeatedly reseed workers on the optimization source
and do not exercise imports.
[9150](https://lab.eignex.com/jobs/9150) keeps the same source/build identities for
`php4` and `sporttournament06`, with 4,000 work units, slices growing from 8 to 64,
reseeding off and Luby-2 restarts. All three repeats reproduce selections, imports,
rewards and termination. `php4` imports 17 peer clauses and credits 19 downstream
uses; the optimization worker observes incumbent versions 3, 4 and 5 before the work
limit. No emergency deadline fires. These are mechanism observations: source-check
receipts are absent, native verdicts remain unverified, and no default activation or
throughput improvement follows.

This repository owns catalog selection, runtime configuration and build/launch recipes.

`bench.profile.BooleanProofCoverageProfile` accepts `suite=ID problem=NAME sha256=HEX
work=N seed=N splits=N` for one DIMACS source with at most 4,096 Boolean variables,
one MiB of input and four partition variables. Its provenance capture requires both
`:klause-cli:installJvmDist` and `:klause-bench:installDist`; use the JVM flags from
the bench manifest and `-XX:ActiveProcessorCount=1`. It records an ordinary F1 control,
two attributed repeats, two independent replicas and bounded task qualification through
actual finite solves. Source bytes, build/runtime identities, logical schedule and
branch traces, preparation time, per-query native work, inprocessing counters, thread
allocation and sampled heap are retained under `$OUT`. Lost, cancelled, stale and
duplicate task completions exercise the same board that accepts scoped native results.
Witnesses and stops leave coverage open; native refutations remain independently
unverified. This diagnostic includes inline trace cost, uses no production task
scheduler and establishes no equal-resource speed or heap benefit. Sampled heap and
literal payload bytes are separate observations, not complete worker memory bounds.
Optional `cost-repeats=1..12` adds two warmup cycles followed by balanced F1/cube/direct-pin
cycles without branch or schedule trace I/O. The direct-pin control executes the same
conditioned queries without a task board. Each control captures inclusive private
preparation, native checking and retirement time, process CPU (including concurrent
JIT/GC), thread allocation and root/total propagation work. Record storage has its
own elapsed observation. Main elapsed time includes source identity capture,
preparation, warmup, tracing, failed attempts and private retirement; lab process
resources also contain startup and final process retirement. These nested scopes
must not be summed with their containers.
`slice-work` and `slice-max-work` default to 8 and 64 for diagnostic overlap;
set them to the configured invocation values when evaluating applicability.

`lab/recipes/` contains the migrated lab recipes. `scripts/convert-lab-recipe.sh`
translates legacy experiments or schedules from stdin without submitting them;
exact legacy confirmation lists require a newly pinned SDK plan and are refused.

Profiles use `scripts/lab-profile.sh` with a main class as their first argument.
The recipe declares build tasks; the script owns CLI paths, corpus properties and
recording options. After installing the bench, `scripts/draw-sets.sh <features-dir>
<references.csv> <sets-dir> [set,...]` runs the migrated seeded named-set generator.

The planner accepts `selections`, a JSON-encoded list of independent selector maps,
merged without duplicate catalog identities. Paired experiments use lab `configs`,
`grid` and `repeats`: `ref` selects a revision and `param.<key>` overrides an adapter
request key. Single-engine requests publish comparison identities independent of
engine; multi-engine requests retain engine in that identity. Reference-only recipes
use `lab/reference.json`, a portable input-only planner that requires only the bench
installation and JDK 25. It omits the Linux CLI and MiniZinc compiler requirements;
the lab reference backend owns execution and its local toolchain.

These workflows require the agnostic lab coordinator and workers (manifest schema 3).
Reference filtering runs after planning. This planner owns selection caps; the lab
does not refill them after filtering. Independent checkers remain in Eignex Lab.
Bench schedules preserve the explicit `bench.sourceValidation` policy.

The adapter accepts `LAB_CORPUS_MIRROR`, `LAB_CORPUS_MIRROR_WRITE`,
`LAB_CORPUS_KEEP_PACKED` and the preparation-only `LAB_INPUT_SIGNER`, with existing
local corpus settings as fallbacks. No lab-specific dependency is added to the core.

## Rewrite impact controls

Use `param.presolve-structural-impact=65536` for bounded rewrite delta counts and
`param.presolve-preserve-structure=true` for the affine/structural control. Keep
both arms on the same revision, source hashes, preparation work allowance and
containing solve allowance. Pair the representation counts with root propagation,
search work, learned literals, admitted LP nonzeros, local-search consumption and
independent source checks. The counts describe representation rather than benefit;
partial impact observations cannot establish full deltas. Disable the impact scan
in timing controls to separate diagnostic cost.

The existing `xcsp3-core/table-substitution-interior`, `-hole` and
`-outside-reader` controls exercise `presolve=default,+table-substitution`
against the default. They qualify exact optimization-scoped value exclusion,
separately from affine Table-column substitution and structural Table reduction.
Use `portfolio-work` only for compatible mixed/LS worker comparisons, retain its
logical trace, and distinguish emergency interruption from completed work.

`bench.profile.RewriteStructureProfile` takes `suite=ID problem=NAME sha256=HEX
presolve=CONFIG`. It captures each structure-control arm through production source
and finite preparation, then reuses projected source hints, maintained definition
networks and core/Table-hull LP assembly. Each arm has a 30-second containing
deadline, five million preparation work units and one million consumer-diagnostic
units; the whole capture has 120 seconds. Sources, lowered coordinates and factors
are capped before consumer preparation. The report retains actual maintained
definitions/input edges/depth and emitted rows/columns/nonzeros. The forced LP
views establish representation effects; ordinary worker admission and whole-source
outcomes require separate adapter runs. Use `suite=rewrite-structure-v1` for the
small affine Table control. The Element source under that identity retains a
legacy start-index placement rejected by the independent checker; use
`suite=rewrite-structure-canonical-v1 problem=element-cone` for its separately
identified standard-list-index control.

## Corpus and build identity

Compressed file inputs are staged with the existing corpus decoder before CLI
launch. `sourceDecodeMs` is deducted from the CLI deadline, and
`sourceInvocationMs` includes decoding, invocation and staged-file retirement.
`elapsedMs` and process witness times retain their subprocess scope. A spent
preparation allowance supplies no solver verdict. Plain source digests and frozen
compressed input identities remain available for independent checking.

`corpus status` lists cached collections, `corpus gc` applies the cache cap and
`corpus compress [path]` compresses supported plain inputs with `zstd` on `PATH`.
The cache defaults to `~/.cache/klause-bench/corpus/`, respecting `XDG_CACHE_HOME`
or `-Dklause.bench.corpusCache`. Set `KLAUSE_BENCH_CORPUS_MAX_GB=N` or
`-Dklause.bench.corpus.maxGb=N` to change its 60 GB cap; `0`/`off` disables eviction.
Prefetch or disable eviction when parallel processes share a cache.
See [MiniZinc setup](../klause-mzn-lib/README.md) for direct MiniZinc runs and the
[smoke corpus](smoke-corpus/README.md) for fixture provenance and licenses.

To reuse installed-build hashes across repeated bench processes, run
`provenance out=/absolute/path/provenance.json` with the installed launcher and set
`KLAUSE_BENCH_PROVENANCE` to that path. Regenerate it after rebuilding or restoring
an installation. Without it, each process captures its own installed-build hashes.

## Weighted objective qualification

`set=weighted-search-qualification-v1` fixes an easy WCNF control, the scheduling
WBO and haplotyping MaxSAT tails, and the weighted `mprime` incumbent control.

`bench.profile.WeightedSearchProfile` qualifies one hash-pinned WCNF/WBO source
through ordinary finite preparation and retained core search. Arguments are
`suite=ID problem=NAME sha256=HEX format=wcnf|wbo policy=ordinary|stratified|reasons|deletion
timeout-ms=10000 work=250000 slice-work=200 seed=3`.
Run it through the lab with a bounded profile allowance. It writes the original
source, installed-build provenance, exact floor/core-weight/threshold and accepted
incumbent trajectories, preparation/threshold/verification visits, query counts,
traversal work and a summary including source preparation and disposal time.
The summary retains slice count and final work after private retirement. Each
accepted incumbent writes its reconstructed source and lowered Boolean values to
`incumbent-N.json`, bound to the source hash and exact objective; array index `i`
names source variable `i+1`. Capture admits at most 128 witnesses and two million
Boolean values; exhaustion fails qualification instead of dropping witnesses.
The timeout contains source preparation, native point checking and trajectory
writing; these instrumented observations establish no throughput claim. Native
Boolean point checks and native bound authority are separate from the lab's
independent source checks and independently validated proofs. Use ordinary lab
adapter runs for source-matched admission and primal controls.

[9178](https://lab.eignex.com/jobs/9178) and
[9180](https://lab.eignex.com/jobs/9180) qualify the scheduling WBO at `d5a65676c0`,
seed 3, with 250,000 work units, 200-unit slices and a 40-second containing
allowance. Both retain the same source hash, 11,110 prepared Boolean columns,
8,382 factors and a reconstructed objective-19 witness. Ordinary search spends
204,186 final work units across 20 queries and 19 unit-weight cores; seed-reason
projection spends 175 across two queries and one six-member weight-19 core.
Retirement is included, and both closed trajectories release their query pins.
Source preparation takes 18–20 seconds before these queries. These instrumented
runs establish consumption and witness custody, without independently checked
optimality or a throughput claim.

[9179](https://lab.eignex.com/jobs/9179) runs the four-source set through eight
CLI controls at the same revision, seed 3, one processor and 40 seconds per case.
On the scheduling WBO, delayed reason projection consumes one weight-19 core and
reports objective 19; delayed ordinary and earlier admission spend their auxiliary
allowance on coarse cores. Direct ordinary and reason search also report 19, while
LS and ALNS retain larger feasible objectives. On `mprime`, the mixed pool reports
562 before core admission; both direct controls spend their allowance on bounds
without a witness. On the MaxSAT tail, direct controls reach floor 5 without a
witness, while every curated core receives zero segments. The candidate delayed
case has no witness where its parent has one, so this elapsed run does not establish
incumbent protection. Retain delayed admission and unrefined cores as defaults;
reason projection remains an opt-in source-specific control, and direct core search
does not replace the primal pool.

Source hashes, full witnesses, installed binaries and resource receipts accompany
these runs. The lab has no WBO/WCNF source-check adapter: requested independent
validation produces no verdict for these formats. All reported feasibility and
optimality claims remain native and unchecked. Source-matched reference rows also
lack independently checked proofs; their objective agreement adds no proof authority.

## Clause sharing accounting

The single-lane work schedule also samples retained clause-sharing memory. CLI arm
records distinguish logical payload, primitive arena/metadata/seen capacity, reference
slots, transient drains, import work and sampling work. Pool samples name one shared
owner; worker samples include native/general learned stores, atom registries and
historical reasons. Individual sampled peaks are not process RSS or concurrent totals.

The lifecycle regressions cover repeated pool compaction with a slow consumer,
retained-session assumption reseeding, fresh-session backing release, suspension,
protected reasons through native/general compaction, and actual satisfaction and
optimization workers consuming peer imports. They preserve the existing use-based
reward drain. Sizing, retention age and targeted delivery are separate experiments.

[9222](https://lab.eignex.com/jobs/9222) captures two repeats of `php4` and
`sporttournament06` at `325dc8d256`, seed 17, one Linux/amd64 processor,
4,000 work units and retained/reseed controls. Source hashes and installed
library/CLI/runtime hashes match; path-sensitive build fingerprints remain in
the records. Each `php4` case imports 17 clauses and drains 16 use-window credits;
13 retained peer imports have consumed consequences. The pool holds 25 clauses
with 372 payload bytes, while a consumer's seen filter peaks at 25 keys/576 bytes,
its learned arena at 512 bytes and metadata at 1,344 bytes. Retirement reports
zero seen capacity. Primitive samples describe sharing owners, whereas process
RSS across the eight cases is 111,716–145,420 KiB and includes runtime and model state.

The optimization reseed control retires the main native worker 32 times and the
private ALNS repair owner 14 times. Its automatic recipe has only one
clause-sharing backtrack worker, so it supplies no peer-clause import evidence.
Both `php4` controls repeat their complete logical trace. Optimization stderr
capture ends at 16,384 bytes: its captured complete events match across repeats,
and incomplete final lines establish no scheduling divergence. Full-trace
reproducibility is unqualified for those captures. Explicit clause-capable workers
separate the sharing control from that mixed recipe.

Preparation spends about 549 seconds, including 234 seconds waiting for a compiler.
All eight native case records are present, but the containing 900-second job ends
with `Whole-run deadline exhausted` before final artifact/check completion.
The requested independent checks supply no receipts; native UNSAT and objective -6
remain unchecked. Earlier HTTP400 schema rejections created no jobs. These records
demonstrate accounting and consumption, not a speedup, independently checked proof,
memory bound or basis for changing retention/delivery defaults.


[9238](https://lab.eignex.com/jobs/9238) isolates two clause-capable CP workers on
`sporttournament06` at `b81cc434e8`, with the existing `pb-learning=false`
diagnostic control. All four native records retain a 156-byte learned payload,
but no clause passes the export filter. The 1,200-second containing job expires
after preparation, execution and finalization; no independent check receipt is
available. This is an export-applicability negative, not peer-consumption evidence.

[9257](https://lab.eignex.com/jobs/9257) captures the native/shared observation
integration at `3d189f2d97` after the root-simplification integration, using the
same two sources, explicit CP workers and one repeat per retained/reseed control.
Both workers report `sharedLearningObserved=1`, 224 bytes of shared learned
index capacity and 80 bytes of shared trail reason index capacity. The shared
learned payload remains empty in these captures. Each `php4` control imports
7 and 8 peer clauses; 7 and 4 retained peer imports respectively have consumed
consequences. Both workers release their seen buffers. Optimization exports no
clauses; the forced control reports 32 and 27 reseeds and objective -5, versus
-6 with retention. Its stderr is truncated, so complete work-trace equality
is unqualified. Process RSS spans 115,544–131,072 KiB. Preparation includes
315 seconds of compilation and 66 seconds of upload. Native case completion
provides no independent check receipt or basis for a performance/default claim.
Additional reason journal, event and root-retirement capacity fields are covered
by CI at the later source revision.

## Propagation and explanation value

`set=propagation-value-v1` retains scheduling, Table and mixed finite arithmetic
sources plus a native SAT control. The lab request parameter
`propagation-controls=true` with `engines=cp` expands each source/seed/repeat into
ordinary, attributed and historical-Table cases in one repository-owned plan. Case
IDs retain the control suffix, and `pairKey` retains their common source identity.
Explicit attribution/refinement overrides conflict with this paired control.

Queue ordering is a separate control: compare `param.cost-aware-propagation=false|true`
with `param.historical-table-reasons=false` in both arms. Match attribution settings
within that pair, and compare refinement at fixed ordering in a separate pair.
The candidate charges its bounded prefix inspection and stable queue shifts to
propagation work; its fixed family estimates add no timers. Retain the same source,
compiled input, compiler/runtime bytes, seed, recipe and containing allowances.
`nativePropagationSchedule{Dispatches,Bypasses,Forced,Work}` distinguishes
actual ordering opportunities and charged queue cost from later search changes.
They are subsets and events, without a causal-benefit claim.

Use `param.propagation-attribution=true` with
ordinary `engine=cp` or fixed-work single-lane portfolio runs. Compare
`param.historical-table-reasons=false|true` at the same source/revision, preparation,
seed, worker recipe and containing allowance. This exposes the existing historical
Table retry; it changes neither priorities nor its disabled default.

Retain CLI `nativeFactor<Family><Metric>` counters alongside total propagation work, nodes, outcomes,
witnesses and independent source-check receipts. `Work` and `ReasonWork` charge
attempts including interruption; `Support`, `UipReasons`, `Learned`, `Consumed`
and `LaterUseEvents` separate construction from downstream consumption. Eager
reason construction is inside factor cost, and shared family credits overlap.
`Unattributed` and tracking occupancy expose bounded metadata coverage. An admitted
or consumed object alone establishes no whole-source benefit. Include preparation,
checking, unsuccessful work and retirement in the containing comparison; preserve
native verdicts separately from independent checks. A priority trial additionally
needs periodic opportunities preventing starvation and lower total cost or improved
checked outcomes before activation.

[9243](https://lab.eignex.com/jobs/9243) compares the three controls at
`8250ff0af5`, seed 17, one Linux/amd64 processor, 2,000 decisions per worker and a
10-second CLI ceiling, with presolve and LP disabled. Source digests and installed
CLI/library/runtime hash maps match within each triple; path-sensitive build
fingerprints differ. Ordinary and attributed controls retain the same logical
work and outcomes on all five successfully loaded sources.

On `cyclic-mod3-sat`, 1,376 units of Table propagation and 1,037 units of lazy
reason work produce 16 completed explanations, eight Table first-UIP visits and
two consumed learned objects. Historical refinement adds 76 work units to the
3,013-unit solve without an accepted improvement, changed search or objective.
`overlap-tables-unsat` similarly spends eight extra refinement units without a
changed outcome. On `magic-square-3`, 41 Linear explanation builds cost 380 work
units; 26 Linear and four AllDifferent first-UIP visits contribute to the same
four consumed objects. These overlapping credits cannot be summed. The linear
knapsack control has four explanations but no first-UIP or consumption credit.
Packed SAT on `php4` records 27 consumed clauses and five later-use events.
These observations locate learning consumers and unsuccessful explanation work;
they establish no counterfactual speedup.

The nine feasible XCSP witnesses have valid source-matched SolutionChecker
receipts, version `2.5.1+ca58fc79a42374d080aac4d1410874e748cf0a05`.
Witness hashes match within each source's triple. Checker platform and resource
details are unrecorded. The receipts establish feasibility; native optimality and
UNSAT reports remain independently unverified.

Preparation is part of this evidence: [9226](https://lab.eignex.com/jobs/9226)
spends 895 seconds, including 476 waiting for a compiler, 249 compiling,
120 selecting and 23 uploading, then exhausts its 900-second whole-run deadline.
Its normal retry retains the exhausted deadline. Job 9243 reuses verified
artifacts in 23 seconds, executes all 18 cases, and exhausts its 1,200-second
containing deadline after worker completion; three scheduling controls fail
loading compressed input. Source checking takes about 100 milliseconds per
accepted witness. Complete finalization/retirement time is unqualified, and these
single, cache-dependent observations support no throughput or RSS claim.

[9270](https://lab.eignex.com/jobs/9270) completes the three frozen RCPSP controls
at `76a5438832` after compressed-source staging. Source and installed build/runtime
hashes match within the triple. Decoding takes 2–3 ms inside the 10-second source
allowance; invocation and staged-file retirement take 10,498–10,539 ms, including
JVM startup and output. All three witnesses pass the source checker in 160–250 ms.
Their objectives are 118, 117 and 121 in control order. Optimization selects the
timed finite CP portfolio, so these different search traces establish no causal
performance comparison. The attributed case spends 30,633,861 scheduling work
units, with 25,425 completed explanations, 12,349 first-UIP visits, 524 consumed
objects and 137 later-use events. It records 38 untracked scheduling observations;
bounded provenance does not cover every reason. Scheduling reasoning dominates
charged propagation and has downstream consumers, without an established net gain.

The focused job finishes in 501 seconds, including 440 preparing: 245 compiling,
128 selecting, 12 archiving and 39 uploading. Its workers commit and request stop.
The earlier [9258](https://lab.eignex.com/jobs/9258) loses a preparation worker and
exhausts its containing 1,200-second allowance during recovery without solver cases.
[9269](https://lab.eignex.com/jobs/9269) is rejected before allocation because its
revision is detached by rebase. Failed preparation and recovery remain charged;
no priority, refinement or attribution default change follows from these captures.

## LP capture and replay

`LinearRootOpeningProfile` accepts `inspect=partial-row-activation` for a pinned
SMT source. It compares the existing cached partial interval producer with the
same producer plus literal-to-row assertion scheduling. Both arms use seed 1,
one processor, a containing 100,000,000-unit open-work allowance and the same
forty-second emergency deadline. Fresh ordinary source preparation precedes each
arm; exact prefix and continuation snapshots must agree. The profile reports row
visits, literal touches, retracted assertions, compatible decline reuse, consumed
guard implications, operation stops and producer/assertion/numerical time separately. These are
direct library diagnostics; they supply neither a default CLI throughput claim nor
independent whole-source proof validation. Run the main through the committed lab
profile wrapper with CLI and bench installation tasks; retain pinned source bytes, preparation,
build/runtime provenance and unsuccessful receipts beside the results.
An optional `sourceGzip64` uses the existing 96 KiB encoded-input loader and the
profile's 32 MiB source cap. Its decompressed bytes must match `sha256`; this lets
a retained original source avoid a cold download of its entire catalog archive.
`source-preparation.json` records that input mode and parsing cost. Packed source
bytes and raw profile records belong in the external evidence directory.
Failed child runs forward at most 16 KiB of their error stream to the parent log.
Retained files and complete parent logs still require lab artifact publication.
`inspect=partial-row-activation-control` runs the same cached producer in both
arms with those limits and snapshot checks, isolating replay variation from row
scheduling. Open-work units count policy decisions and checks; they do not count
primitive arithmetic operations or replace preparation and numerical timings.

The prepared-factory strict-bound, backtrack and compatible-decline controls pass
on JVM and Linux native at `2337dbd102`; the new JVM cases take at most 5 ms.
Original PWD [9192](https://lab.eignex.com/jobs/9192) stops during cold corpus
acquisition, and [9215](https://lab.eignex.com/jobs/9215) stops at the missing CLI
provenance installation. Frozen-source [9230](https://lab.eignex.com/jobs/9230)
reaches 3 of 8 boundaries in its cached control before the ten-second emergency
deadline, reporting 3 deductions and 2 deferred numerical queries. Its delayed
artifacts identify that limit failure; the incremental arm did not run.
Diagnostic [9244](https://lab.eignex.com/jobs/9244) reproduces that control limit.
With thirty-second deadlines, [9262](https://lab.eignex.com/jobs/9262) reaches the
first boundary in both arms with identical exact states, shared pins and reasons.
Incremental mode consumes 16 deduction events with 5 numerical deferrals and
119,049 source-row visits, versus 396,676 control visits. After continuation,
shared pins, reasons and source assertion levels still agree, but LP witness IDs
and five point coordinates differ; the profile rejects that continuation replay.
[9268](https://lab.eignex.com/jobs/9268) repeats the cached producer in both arms;
its prefix and continuation snapshots agree. Both continuation snapshots are also
byte-identical to the incremental continuation in 9262, with 37 cumulative
deduction events and 18 checks. The first paired control's differing intermediate
work is retained as an unqualified comparison; it establishes no scheduling or
throughput attribution. At the matching first boundary, only 1,871 of 11,671
Booleans are assigned, establishing consumption before source completion.
Current-main [9272](https://lab.eignex.com/jobs/9272) at `7d687be9eb` passes both
paired snapshot comparisons with forty-second deadlines and zero operation stops.
Its incremental continuation records 37 cumulative deduction events with only
2,267 of 11,671 Booleans assigned, 32,582 assertion invalidations and 63,444
compatible decline reuses. Source-row visits are 260,909 versus 736,684 in the
cached control. Linux amd64 JDK 25.0.4.1+1 runs under a hard one-core, 4,608 MiB
cgroup allowance; the measured JVM uses a 3 GiB heap and Serial GC. Original
source, installed build/runtime hashes and exact states accompany the lab record.
These observations complete the original-source consumption audit for
[#3844](https://github.com/Eignex/klause/issues/3844); they establish no default
throughput or terminal claim. Admission stays opt-in.

`lp-component-split` supplies existing separable MPS and MiniZinc sources plus
`mzn-linked-8x24` as a connected control. For immutable node lifecycle controls use
`param.lp-component-ownership=true|false` with identical source hashes, presolve,
LP effort and containing work limits. This option also dispatches root tableau
cuts to their owning blocks. It leaves automatic component admission unchanged;
record prepared block counts and actual bound edits before interpreting a zero
reuse or zero-cut result. Component count alone establishes no saved work or
whole-source improvement.
`lpPhase_COMPONENT_LIFECYCLE_*` records child `SOLVED` and `REUSED` counts through
the existing route ledger; numerical work, pivots and factors count actual solves.
`SOLVED` counts invocations, including attempts that stop without a result.
`set=lp-component-lifecycle-v1` selects two binary covering MPS sources with direct
additive objectives. Each block needs two selected columns but has a fractional
LP optimum, admitting local tableau cuts without an objective-definition row that
connects the blocks. Compare ownership and `param.lp-root-cuts` independently;
retain prepared block counts, child reuses, cut consumption and ordinary outcomes.
Pin `param.bt-arm=lp-aggressive` and `param.arms=1` for lifecycle controls so an
LP-free portfolio arm cannot finish the source before the component owner runs.

Cut lifecycle controls retain `lpPhase_CUT_ROOT_*` and `lpPhase_CUT_NODE_*` phases
alongside the ordinary LP totals. Family tags distinguish construction, mapping,
admission recipe (`ORDINARY`, `STRUCTURAL` or `COMPONENT`),
admission, selection, aging, retraction, checked support and actual deduction use.
`*_SOLVE`, `*_OWNER_EDIT` and certificate phases are shared containing costs;
families participating in one query overlap and must not be summed. `*_FILL`
reports the sum of completed-query maximum fill ratios in millionths, divided
by its call count for an average. `*_BASIS_BUILD` reports actual backend build
work, declined builds, missing reports and successfully installed factor entries,
including work from failed solves. These units overlap the numerical cost model;
retain ordinary numerical failure and work counters too.

For finite controls compare `param.lp-root-cuts=false|true` under the same pinned
LP arm. For numerical source traversal retain a no-cut arm
(`param.source-cuts=false`), current cuts (`param.source-cuts=true`) and the
existing point selection trial (`param.source-point-cuts=true`). Keep aggregation
off unless it is the hypothesis under test. `numericalCutLifecycle*` separates
installed rows, checked bound/conflict proposals and actual frontier closures;
family work and allocation include failed containing operations and overlap the
ordinary source ledger. `numericalCutLifecycleRECIPE_*` retains each complete
no-cut, current-cut or point-selection query cost once. Family preparation and
allocation are shared among attempted families; numerical and certificate phases
name families actually installed in the query. Proof-verification work includes
declined and unselected rows. Identical source hashes, invocation allowances, numerical
policy, presolve, seed and runtime are required before interpreting a difference.
Point selection also checks complementary continuous bound choices within the
existing mixed-rounding family and shared limits. `numericalCutMixedEligibleRows`
counts source rows once; `MixedCandidates`, `MixedComplementaryAttempts` and
`MixedComplementaryRetained` distinguish candidate work and retained alternatives.
Bound-side alternatives and selected rows establish no useful bound or cost gain
without subsequent checked support and actual frontier consumption.

[9277](https://lab.eignex.com/jobs/9277) exercises both sources at `0af8492830`
with ownership and root cuts toggled independently. With root cuts off, node
queries reuse 4 and 27 child results; with cuts on, 2 and 3 cuts become active.
All eight source witnesses pass the independent MIPLIB solchecker with zero
violations and objectives 4 and 6. Witness checking does not certify optimality.
Preparation takes 6.4 seconds through a verified build cache; the preceding
[9275](https://lab.eignex.com/jobs/9275) takes 333 seconds to prepare and retains
the unpinned controls that often finish in LP-free arms. Earlier admission,
worker-loss and deadline failures retain no numerical outcome. These small
controls support lifecycle consumption, without a timing or default-activation claim.

The opt-in basis tool uses the [basis corpus](../klause/src/jvmTest/resources/basis-corpus/README.md):

```sh
./gradlew :klause:basisTrace --args="list"
./gradlew :klause:basisTrace --args="verify"
./gradlew :klause:basisTrace --args="replay trace=/absolute/path/to/trace.kbtrace"
```

Run captures, timing comparisons and profiles through the lab. Harness unit tests
use `./gradlew :klause-bench:test`; they do not run benchmark campaigns.

### Claim-specific LP certification

Certification diagnostics use the existing `lp.phases` map. `CLAIM_BOUND`,
`CLAIM_CONTRADICTION`, `CLAIM_FEASIBLE_COMPLETION` and `CLAIM_ATTAINED_OPTIMUM`
are keyed by consumer route, with inclusive nanoseconds, charged certificate work,
and satisfied, reused or refusal outcomes. `CLAIM_CONSUMPTION` records accepted
leaf packages and pruning consequences separately. Nested phase time and claim work
overlap the existing certification counters; do not sum them. Source optimization
exports deterministic claim work and outcomes under `CertificationCLAIM_*` in its existing stage map.
Source active time stays in its containing budget rather than its logical trace.
Compare costs with consumed claims and preserve unsuccessful requests. A cached bound
without a point cannot satisfy attainment. Reconstruction attempts, cache admission,
exact acceptance and resource ceilings retain their production settings.

[Claim regression controls](https://github.com/Eignex/klause/pull/3865) cover
production open refutation, real-leaf completion and repeated unchanged-leaf
consumption, plus stronger requests, scoped invalidation and policy refusal.
The nine initial JVM claim tests on `a8ba2a4a8f` take 0–11 ms each; JVM, Linux
native and lint/docs CI pass at that revision.
[9239](https://lab.eignex.com/jobs/9239) retains a failed applicability control:
finite-only portfolio-work flags reject all six mixed numerical cases before solving.
[9251](https://lab.eignex.com/jobs/9251) removes those flags and pins the same
three vendored MPS sources, `d43182fe77`/`a8ba2a4a8f`, exact mode, presolve off,
one lane and seed 17 under a 600-second containing allowance with source validation.
Its receipts distinguish preparation, solve, witness checks and native proof flags.
These controls establish no throughput improvement or independent proof validation.

### Numerical lifecycle profiles

`scripts/lp-template-preparation-profile.sh <source> [repeats] [steps]` accepts
`cover-2x3`, `cover-3x3`, `dense-2x12` and `dense-8x24`. It compares disabled
templates, object reuse, complete index reuse and selective metadata reuse on the
existing MPS component fixtures. Submit as a lab profile script with build task
`:klause:stageLpProfileJvm`, Linux x86_64 placement and a 180-second runtime cap.
It includes repeated cold preparation, unchanged views, alternating bound, objective
and row edits, and cancellation through actual relaxation assembly and native LP
certification. Records
retain source bytes/hash, total preparation/assembly/solve work, time, allocation,
modeled retained units and negative results. One warmup precedes up to five measured
rounds; mode order rotates. Equal native results across modes check consistency,
without establishing independent source or optimality validation.

The unchanged-index controls [9363](https://lab.eignex.com/jobs/9363) and
[9369](https://lab.eignex.com/jobs/9369), at `69cc7b2250`, compare all three modes
through actual consuming LPs on the two frozen P3 MPS fixtures. Including cold
setup, median preparation-plus-assembly falls from 4.73 to 4.10 ms over 32
consumers and from 5.79 to 5.27 ms over 64; allocation falls by 202/353 KB.
Charged template work falls from 5741/7454 to 1742/2036. Ordered coordinates add
9/6 modeled retained units; the publication/storage cap stays unchanged.
Alternating bounds, objectives and rows rejects complete-index reuse and retains
selective rebuilding, including failed-validation cost. Objective churn increases
assembly time by 7.1/2.7 percent in these controls. Disabled templates remain
cheaper on these tiny sources, and whole-LP timings support no speedup claim.
Native LP results agree across modes; their optimality is not independently checked.

`scripts/lp-lifecycle-profile.sh mps-afiro|mps-adlittle [repeats] [both|jvm|native]` runs one
hash-pinned source from the existing basis corpus on JVM and Linux Native through
the production source projection and scoped LP owner. Submit it as a lab profile
script with build tasks `:klause:stageLpProfileJvm` and
`:klause:linkLpProfileReleaseExecutableLinuxX64`, `base.timeout="180000"` and
compatible Linux x86_64 placement. Fund remote build, source preparation, retries
and cleanup separately through a finite `profile.runTimeoutSec` containing
allowance. These bench-owned custom compilations have
internal access to the engine and add no Maven artifacts. Ordinary CI compiles
them and runs the shared deterministic regressions; it runs no profiling workload.
When requested together, JVM profile staging precedes native compilation to avoid
overlapping both compiler heaps in a bounded preparation worker.
Separate runtime jobs request only the selected target's build task. They retain
their own CPU identity and resource receipts; different placements do not support
an absolute cross-runtime speed comparison.

The optional fourth script argument claims selects retained, observed requests
for each claim kind. It includes unchanged repeats, an equal-valued replacement
authority, bound and row edits and pops, objective-constant changes and row
deactivation, with one warmup and the requested measured rounds. Records expose
state revisions, ordered-basis/status comparisons, checked-result reuse and storage
declines,
reconstruction work and rejection details, continuation import position and
cumulative-limit behavior, and refinement phase and lifetime spending. These are
diagnostic controls; unchanged requests and changed scopes are separate samples.
All work stays under the existing 120-second containing profile deadline. The
ordinary source worker follows the controls under its own nested deadline.

The fourth argument `precision` pairs fixed-width-first and early arbitrary precision
through the same claim and authority controls, alternating arm order. Both keep
the same cumulative numerical and verification limits. It also pairs real source
workers through their internal consumer context, each with five seconds and the
existing 1,000-instruction ceiling. Other modes keep the two-second worker control.
Use complete query/certification time and actual allocation alongside import
progress; whole-process RSS spans both arms. The `consumers` mode runs only the
paired source workers for follow-up integration controls.

Each owner runs cold, asserted-bound, bound-pop, appended-row and row-pop queries.
Fresh owners at the same exact states control retained owners. One warmup precedes
up to five alternating rounds, with phase observations enabled and disabled.
Preparation, edits, query, certificate phases and retirement remain in the record.
JVM thread allocation and whole-process RSS are separate measurements; native
allocation is unavailable. Inclusive numerical and certificate phase timings must
not be summed. Feasibility repair includes its transforms, pricing and updates;
violation scans have a separate nested observation. Checkpoint wall time,
including suspended caller time, is reported separately from repair. Query
time less floating time includes certificate work, query scaffolding and bounded
completion-trace materialization. The enabled/disabled comparison includes that
diagnostic overhead. Record emission follows each query and
enters the containing lifecycle time. The separate active total excludes record
emission. Preparation and edit
phase deltas include operations on retiring and replacement engines; invocation
traces reset at the next solve. Scoped owner counters are cumulative gauges and
must not be summed across queries.

The same executable runs a fresh direct `satOptimized` worker on each runtime,
with seed 3, 1,000 decision instructions and a two-second containing deadline.
Its factory returns the production numerical owners with their resumable
capabilities intact. Completion observers retain active numerical time, work,
bounded traces and existing claim/certificate phases; pauses publish no completion.
Containing cancellation closes the active owner and emits `profile-stop` with
`complete=false`; partial records do not establish a completed capture.
The worker record includes consumed LP prunes/fixings, its native result and full
worker-coordinate sample. It establishes no CLI routing equivalence or independent
source validation. Preparation, failed work and retirement remain in the worker
allowance. Whole-process RSS spans the controlled lifecycles and this worker;
it is separate from JVM per-lifecycle allocation.

Original bytes, corpus manifest, revision, executable hashes, runtime, JFR and
resource receipts accompany the records, including the official JFR allocation
sample export. Numerical invocations retain the bounded progress samples and
certificate work retains exact-input eligibility and declined reconstruction
outcomes. These controlled LP edits establish no
original-source result. Pair them with source-matched ordinary lab solver runs
using independent source validation, deterministic work scheduling where applicable,
and the same semantics and preparation controls. Kernel timings, pivot counts and
modeled work alone establish no numerical policy improvement.

[9279](https://lab.eignex.com/jobs/9279) repeats the ordinary `pure-real-min`
source at `ec38f24632` and `f633ad442c`, with exact mode, presolve off, seed 3
and one processor. All six native results retain objective `1/3`, three standalone
pivots and 220 LP work units. Independent checking is unsupported because the
checker accepts decimal witness coordinates only. The requested `gr4x6` selector
matches no `miplib3` input. [9285](https://lab.eignex.com/jobs/9285) retains matching
objective `3/2` on `open-real-min`; its independent checker rejects the MPS layout
before checking the witness. Neither control establishes independent feasibility.

[9286](https://lab.eignex.com/jobs/9286) repeats `blend-tiny` on the same revisions
and runtime with 1,000 finite work units and fixed 200-unit slices. All six original
witnesses at objective 9 pass the external solchecker with zero violations. The
seven complete scheduling events match across all cases, ending after 29 charged
work units without an emergency stop. Installed distributions match within each
revision's repeats; the source and CLI hashes match across revisions. These are
source and scheduling controls, not a throughput result or independently checked
optimality proof.

[9293](https://lab.eignex.com/jobs/9293) and [9294](https://lab.eignex.com/jobs/9294)
capture Afiro on JVM and Linux Native at `e288e79cec`: 80 controlled queries per
runtime, including warmup and three measured rounds. All corresponding exact
results, points, terminations, pivots and charged work match across observation
settings and runtimes. Each real worker consumes an attained-optimum claim after
20 numerical pivots and 21,937 work units. Its captured coordinates remain separate
from independently checked original-source claims.

[9295](https://lab.eignex.com/jobs/9295) captures three Adlittle JVM rounds;
[9300](https://lab.eignex.com/jobs/9300) captures one Native round plus warmup at
the same revision. All 120 controlled queries remain indeterminate after a floating
optimal candidate: reconstruction declines `CANDIDATE`, continuation reaches
`IMPORT_LIMIT`, and refinement reaches `WORK`. Corresponding completed controls
retain identical results and work. Both real workers return `Unknown` at their
two-second limit, spending 1.65 seconds on JVM and 1.94 seconds on Native in the
exact ladder. These failed claims remain part of the cost.

Retained edit-stage observations expose the remaining cost. Entries below are
ranges of stage medians; the Native Adlittle capture has one measured sample per
stage. Claim time is inclusive and must not be added to numerical time.

| Source/runtime | Numerical solve (ms) | Attainment claim (ms) | Timed process wall (s) | Peak process RSS (KiB) |
|---|---:|---:|---:|---:|
| Afiro/JVM | 0.153–0.273 | 2.80–3.51 | 2.93 | 126,632 |
| Afiro/Native | 0.036–0.047 | 9.06–10.76 | 1.51 | 30,436 |
| Adlittle/JVM | 0.102–0.194 | 494–559 | 58.20 | 187,376 |
| Adlittle/Native | 0.131–0.152 | 1,740–1,867 | 98.61 | 42,192 |

The process totals include warmup, diagnostics and the real worker; builds and
source transfer have separate lab receipts. Different round counts, runtime startup
and recording overhead prevent an absolute cross-runtime speed comparison.
Adlittle's five-query JVM lifecycle allocates a median 1.03 GB with fresh owners
and 0.84 GB with retention, with observations disabled. JFR allocation samples
concentrate in exact continuation pivots and fraction arithmetic. Native allocator
totals are unavailable; process RSS does not replace them.

Retain the existing pricing, edge-weight, update/refactorization and allocation
policies. The retention control removes most numerical work while certification
dominates the remaining elapsed cost, including unsuccessful requests. These
controls identify no justified numerical policy change or default activation;
fewer pivots alone would misrepresent the Adlittle outcome. Exact acceptance and
resource ceilings remain intact.

Preparation failures in [9276](https://lab.eignex.com/jobs/9276) and
[9282](https://lab.eignex.com/jobs/9282) precede numerical execution. The three-round
Native Adlittle capture [9296](https://lab.eignex.com/jobs/9296) reaches the
120-second containing limit after 49 completed queries and one cancelled query;
it never reaches the real worker. Its 61 complete JSON records precede an aborted
launcher and are partial evidence. [9298](https://lab.eignex.com/jobs/9298) loses
its preparation daemon before running the structured-stop revision. None of these
outcomes supplies a numerical improvement or a completed capture.

### Early exact-import precision

[Adlittle 9361](https://lab.eignex.com/jobs/9361) pairs the internal early-big
request with fixed-width-first continuation under unchanged ceilings. The measured
14-state attainment-request lifecycle takes 1.67 s and allocates 320 MB versus
7.57 s and 1.35 GB. Early big completes 93 imports; fixed-width overflow replay
spends 128 cumulative imports and stops at position 102/154. Conservative modeled
cold import/check spending rises from 25.5 to 35.8 million work units and from
147 to 242 MB, within the existing limits. Returned feasibility does not satisfy
attainment: the exact bound remains unavailable under the existing reconstruction
and refinement allowances.

[The real JVM consumer](https://lab.eignex.com/jobs/9372) returns a best-known
Adlittle point with one consumed feasible-completion claim under early big;
fixed-width-first returns Unknown at the import ceiling. Both finish inside the
equal five-second allowance. The observed invocation costs are 1.69 s/113 MB and
3.59 s/315 MB; fixed-first worker order includes warming effects, so these are not
a general throughput estimate. Process RSS spans both arms. The worker point is
native checked, without an independent Adlittle source receipt or optimality proof.
[Afiro 9373](https://lab.eignex.com/jobs/9373) preserves consumed attainment and
compatible checked-package reuse in both arms. [Ordinary controls 9375](https://lab.eignex.com/jobs/9375)
independently accept the original points/objectives 9 and exact 1/3; they do not
independently prove optimality. Arithmetic selection stays opt-in, with numerical
defaults, ceilings and proof acceptance unchanged.

### Bounded sparse admission trials

`lp=default,+sparse-trial` selects the ordinary model-facing LP route's bounded
root trial. Compare it with `lp=default` at the identical revision, source,
preparation policy, seed and allowance. Admission emits `lpSparse*` statistics for
nonzeros, component dimensions, scalar width, row-intersection risk, reserved
storage and decline reasons. Ordinary LP work, pivots, factor fill, certification,
root bound, prunes and fixings establish progress and consumption. An admitted
trial or completed lab job alone establishes no useful deduction or source proof.
Keep physical RSS separate from modeled storage reservations. Source checking and
all preparation, failed work and retirement belong to the containing comparison.

[9172](https://lab.eignex.com/jobs/9172) compares default and trial at
`bde182ce9b`, seed 3, ten seconds and one Linux/amd64 processor, with matching
source and installed-build/runtime hashes. On `cap6000`, 18,243 nonzeros across
2,171 rows and 6,000 columns admit the trial. Native exact certification accepts
the root lower bound -2,451,537, and ordinary search exports it to the shared
objective floor; the root owner is retained for subsequent queries. The trial
finds no witness. The default witness at -2,442,801 passes the external MIPLIB
solchecker; that receipt certifies the witness, not the trial's bound or optimality.
On `rentacar`, the ordinary root-node query reaches 132 pivots and reduces sampled
violated basics from 815 to 794 before its work stop, without a certificate.

The trial reserves about 529 MiB and 669 MiB respectively; measured process RSS
peaks at about 258 MiB and 462 MiB. Preparation includes 287 seconds of remote
compilation, and the four-case job takes about 417 seconds. These single-repeat
controls establish bounded execution and useful native bound consumption, with
large-source no-yield evidence retained. They establish no whole-source speedup,
independent source proof or basis for default activation. Earlier controls and
negatives remain in [9134](https://lab.eignex.com/jobs/9134) and
[9166](https://lab.eignex.com/jobs/9166).

## Worker admission qualification

For phase/lifetime diagnosis use `param.portfolio-progress=true` to retain physical
creation/query/retirement, incumbent availability/read/import/consumption and settled
phase/cost events in CLI stderr. `armPhase.*`, `armLifecycle.*` and
`portfolioInvocationNs` statistics retain bounded summaries even without streaming.
Compare `param.companion-after-incumbent=true` or a separately varied
`param.companion-active-ms=N` with the same admission, rewards, coverage, source,
seed, preparation and containing core-time allowance. The default lifetime is
4,000 ms; changing it does not extend the outer deadline or LP allocation/work limits.
Keep reward, coverage and admission experiments separate. A private incumbent read
is not an accepted import, and consumption is not an improvement. F1's finite work
schedule does not reproduce mixed numerical elapsed schedules. Full-trace claims
require the capture's complete artifact marker; source outcome claims require the
corresponding independent checker receipts.

`param.source-incumbent-completion=true` trials advisory discrete incumbent
completion under the existing numerical and joint-repair companion allowances.
The default is false. `numericalExternalCompletion*` and
`jointRepairExternalCompletion*` distinguish attempts, reused declines, retention
exhaustion and source/objective-checked points; ordinary import/consumption events
remain the downstream evidence. Recovery counters retain preparation, numerical,
exact and checking work. Compare source-matched on/off arms without changing the
admission gate, active lifetime, rewards or coverage. A completed point or a pool
read alone establishes neither consumption nor a performance benefit.
`*CompletionOriginalChecked` records the reconstructed original-source and adjusted
objective check before import on ordinary linear-source execution.
`*CompletionNanos` includes seed preparation, retained-attempt lookup, exact recovery
and checking for every call, including reused and failed attempts. It is a subset of
companion active time. Approximate real seed coordinates guide exact rational
reconstruction before residual LP completion; reconstruction and original-source
checking remain charged to the containing funding. A reconstructed point can avoid
an LP operation, but that alone establishes no source-level benefit.

[9366](https://lab.eignex.com/jobs/9366) exercises ordinary finite producers on
`bell3a` and `bell5`, with completion off/on at `89d5153cd9`, seed 1, one core and
thirty seconds. Each treatment records two numerical imports/uses and one repair
import/use; both final treatment points pass the independent rational MPS checker.
The `bell3a` control fails that checker's zero-tolerance row E3 check, and the checked
`bell5` treatment objective is worse than its control. Preparation costs 546.5 CPU
seconds separately from solving. These controls establish consumed completion,
without qualifying activation or speed. Reconstructed-source qualification and
current-head CI are retained with [the implementation](https://github.com/Eignex/klause/pull/3897).

`param.companion-integers=true` trials bounded general-integer/continuous numerical
companions on the ordinary finite route. `false` retains the binary-only gate.
Both settings capture `admission` lines with fragment, objective, proof authority,
source mapping, retained state, setup dimensions, numerical resource limits and
explicit decline reasons. Capture dimensions are setup estimates, not measured RSS.
Use source-matched current default, binary-gate, expanded-gate and direct
`param.linear-traversal=numerical-frontier` controls with identical preparation,
seeds and containing budgets. Preserve qualified binary sources as admission
controls and the broad numerical-first recipe as a separate allocation control.

Compare independently checked feasibility and original objectives, routing and
construction time, worker initialization/segments/active-time retirement, source LP
work and allocation, publication checks, total wall/core time and measured RSS.
Failed preparation, checking and retirement belong to the containing comparison.
F1's work-driven mode remains available for finite integer/Boolean controls and
rejects numerical companions; mixed numerical elapsed schedules are not reproducible
work traces. S1's directional LP gains remain a separate opt-in branch control.
Native completion flags do not independently validate proofs.

[The admission contract](https://github.com/Eignex/klause/pull/3859) is exercised by
[9267](https://lab.eignex.com/jobs/9267) and
[9271](https://lab.eignex.com/jobs/9271) at `40f5f3353e`, whose tree matches its
rebase merge `8b7b791202`. Both retain matched original source hashes, identical
installed CLI/library and JDK artifact hashes, and Linux/amd64 resource receipts.
The 24 reported witness records pass MIPLIB solchecker
`1.0.4+eignex-lab-longnum-1`; its binary, patch, source and witness identities remain
in the receipts. These checks validate points and original objective values under the recorded
tolerance policy, not independently checked optimality or bounds.

The ten-second run uses four MIPLIB 3 sources and five controls: automatic default,
binary gate, general-integer trial, direct numerical frontier and broad companions.
The trial admits `bell3a`, `bell5` and `flugpl`; `dcmulti` remains a binary admission
control. Both companions receive real slices. The trial loses the `bell5` witness
and worsens `bell3a` against the binary control. Grouped execution permits the last
case to consume about 22 CPU-seconds in ten seconds of wall time, so these outcomes
alone cannot establish a precise policy comparison.

The follow-up runs `bell3a` and `bell5` at thirty seconds, seeds 1 and 3, with one
core and serial execution. The trial and binary control each find four checked
witnesses. Trial objectives improve once and worsen three times; peak RSS increases
in three comparisons. User CPU time stays near thirty seconds per case. The longer
budget recovers feasibility without a consistent quality or total-cost advantage.
The numerical companion produces 101–134 native certified node bounds and records
26–101 child-bound gains before retiring after 4.01–4.18 seconds of active work.
It supplies no terminal completion or credited incumbent, and neither companion
observes an imported incumbent. Construction, deferred queries, private retirement
and source checking remain charged to the containing run.

Keep the binary-only automatic gate and the opt-in general-integer control under
these quotas. This decision concerns the tested admission policy; internal bounds
and changed finite schedules do not establish consumed whole-source benefit.
[9223](https://lab.eignex.com/jobs/9223) exhausts its allowance selecting the full
MIPLIB 2017 archive; [9253](https://lab.eignex.com/jobs/9253) exhausts preparation
after lease losses. Neither produces source outcomes. The successful runs retain
about 397 seconds of cold preparation and nine seconds with reused artifacts,
plus about 2.57 seconds of independent checking; failed preparation stays part of
the investigation cost rather than becoming a solver regression.

## Prepared arithmetic learning

`bench.profile.OrdinaryArithmeticLearningProfile` takes `suite=ID problem=NAME
sha256=HEX timeout-ms=10000 nodes=4096 lp=auto|off trace=first-integer|initial`.
It runs clause and opt-in
integer-row arms through ordinary finite preparation and backtrack traversal,
without a decision replay. The existing bounded arithmetic trace records source
conflicts, historical endpoint rows, mapped admission, retained consumers and
retirement. Its live window admits 1,024 events and 65,536 words. Clauses above
64 literals and endpoint markers above 32 words produce explicit omission events
and a payload-omission count. A
trace with omissions cannot establish the omitted premises. The selector wrapper
delegates all decisions and notifications. Branch records keep only the chosen
coordinate and its domain; typed-row records keep their historical endpoints.
The integer arm's `trace=first-integer` keeps the most recent conflict window until
an integer object is admitted, then keeps its consumers, branch markers and
retirement. Other post-admission details are omitted. Discarded-window/event/word
counts and omitted observations are explicit; the live event and word caps stay
fixed. `trace=initial` keeps the initial window instead. The clause arm keeps its
initial window. Deadlines, decisions and total work/lifecycle counts cover the
entire invocation; discarding diagnostic data resets no solver allowance.

Plain source bytes are hashed after corpus decompression with a 1 MiB input limit.
A catalog hash mismatch retains the bytes and observed digest and stops before solving.
Optional `source64=BASE64 format=FORMAT` uses the existing 96 KiB encoded-input
loader and requires the same source digest, avoiding runtime corpus discovery.
Original source bytes, canonical original/prepared models and installed provenance
remain in the capture. Bounded records also go to stdout, including both canonical
models, so retained live output can expose a capture awaiting file publication.
The live stream includes build/runtime provenance and references its fingerprint
from each arm. Canonical models admit 128 KiB each and live output admits 3 MiB.
A terminal drain allows five seconds plus 1.1 seconds per 64 KiB log chunk for
asynchronous publication; it is inside the profile allowance and outside the arm
timer. Each arm's deadline contains loading, preparation, solving,
reconstruction, native point checking and retirement. Native propagation work is a
subset of invocation cost; the arm timer excludes record serialization and output.
Set the profile execution limit separately from `profile.runTimeoutSec`, which
contains remote preparation, cache work, retries, capture and retirement. The
instrumented capture supplies neither independent
source checking nor a throughput claim. Use an ordinary lab adapter comparison
with source validation for whole-source outcomes; a missing consumer requires
further source or integration diagnosis. Defaults and export scope remain separate.

The [prepared-source regression](https://github.com/Eignex/klause/pull/3856) parses
FlatZinc search annotations and uses ordinary solution-preserving preparation and
production enumeration. The same guarded retained object owns different later
upper bounds, then retires after its guard is negated. Twenty reconstructed source
points satisfy both original relations. At `1a9a2cb15a`,
[CI](https://github.com/Eignex/klause/actions/runs/40863068552) passes JVM, native
and lint/docs; the prepared-source JVM test takes 62 ms.

At `f7eeda662c`, [lab 9307](https://lab.eignex.com/jobs/9307) parses frozen original
`MagicSquare-sum-06_c23` XCSP3 bytes with digest
`f8bf86880a7020f41783f247b670c613f7a4615c640f5764210b630f20e53af2`.
Ordinary preparation retains the overlapping linear sources. The trace includes
the historical endpoint rows, complete fallback and an unguarded 16-term consequence,
mapped to level 12 and stored as identity 0 / factor 1490. That same object forces
variable 9 at most 19, then 16 after pick 48 and 15 after pick 49; sibling reuse also
survives undo. The event cap cuts later observations, so retirement of this object
is established by the prepared-source regression, not by the catalog trace.
Catalog lifecycle counters report 16 admitted/consumed rows and 16 retired rows.

Both arms retain seed 3, a 10-second deadline and `maxDecisions=4096`. The clause arm
returns SAT with native reconstruction checks; the integer arm stops at its node
limit as Unknown. Neither supplies independent source certification or a speedup.
The containing run takes 282.799 seconds, including 87.923 seconds of compilation;
the source command takes 33.769 seconds including output drain. Placement is automatic
on macOS arm64, JDK 25.0.4.1+1, with advisory one-CPU / 4,608 MiB resource receipts.
The smaller magic-square source's AllDifferent-only conflicts, compressed-byte
hash refusal, worker network failure, earlier truncated captures and preparation
timeouts retain their costs and provenance in the external campaign ledger.
