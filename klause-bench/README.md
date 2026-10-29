# klause-bench

JVM problem catalog and single-case runner for
[eignex-lab](https://github.com/Eignex/eignex-lab). Use the lab for experiments,
revision comparisons, reference solvers, witness checks, schedules and profiling.

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

The adapter requires Linux x86_64, JDK 25 and GNU time. A minimal lab request is:

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

Profile mains live in [bench/profile/](src/main/kotlin/com/eignex/klause/bench/profile/)
and run as lab profile jobs; consult the selected main for its arguments and
retain its output through `$OUT`.

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

## LP capture and replay

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

`scripts/lp-lifecycle-profile.sh mps-afiro|mps-adlittle [repeats]` runs one
hash-pinned source from the existing basis corpus on JVM and Linux Native through
the production source projection and scoped LP owner. Submit it as a lab profile
script with build tasks `:klause:stageLpProfileJvm` and
`:klause:linkLpProfileReleaseExecutableLinuxX64`, a 180-second profile limit and
compatible Linux x86_64 placement. These bench-owned custom compilations have
internal access to the engine and add no Maven artifacts. Ordinary CI compiles
them and runs the shared deterministic regressions; it runs no profiling workload.

Each owner runs cold, asserted-bound, bound-pop, appended-row and row-pop queries.
Fresh owners at the same exact states control retained owners. One warmup precedes
up to five alternating rounds, with phase observations enabled and disabled.
Preparation, edits, query, certificate phases and retirement remain in the record.
JVM thread allocation and whole-process RSS are separate measurements; native
allocation is unavailable. Inclusive numerical and certificate phase timings must
not be summed. Feasibility timing covers violation scans; transform, pricing and
update costs during repair remain in their respective numerical phases. Query
time less floating time includes certificate work and query scaffolding.
Serialization follows each query and enters the containing lifecycle time.

Original bytes, corpus manifest, revision, executable hashes, runtime, JFR and
resource receipts accompany the records. These controlled LP edits establish no
original-source result. Pair them with source-matched ordinary lab solver runs
using independent source validation, deterministic work scheduling where applicable,
and the same semantics and preparation controls. Kernel timings, pivot counts and
modeled work alone establish no numerical policy improvement.

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
