# Table search throughput

The paired klause-lab experiments isolate table-heavy backtracking on HSP-table-14409_c23,
HSP-table-12407_c23, and HSP-table-13408_c23, with threeClauses, linearLE, and permutation3 as fast controls.
They pin one satOptimized or conflictDriven arm, one processor, seed 3, and three alternating repeats
on the same lab host. `nodes.json` allows 100 nodes and 30 seconds; `time.json` allows 10 seconds.
Both name exact baseline and candidate commits. Submit with klause-lab's `deploy/lab run <file>`.

- Baseline: da388ebcd1c20580bfe061c7a9811829e8cb2efc.
- Candidate: 234a97c76 (the full commit is recorded in each JSON).
- Node experiment: http://192.168.50.104:8420/jobs/549.
- Time experiment: http://192.168.50.104:8420/jobs/550.

Before changing the propagator, local JFR CPU profiles pinned each arm at 100 nodes on
HSP-table-14409_c23 using JDK 25, Serial GC, a 3 GB heap, and ActiveProcessorCount=1.
Of samples with a PropagationSession pin or learned-clause stack, TablePropagator.propagateBitset
appeared in 165/182 for satOptimized and 528/590 for conflictDriven. Root session construction
accounted for 24 samples in each recording (310 and 797 samples total). The leaf samples concentrated
on support gathering; reversible tuple removals also appeared in the profile. The HSP relations use
ground cells, so stopping a completed column's support gathering matters separately from filling
short-support intervals by words. The live sparse-set prefix is trailed once per completed sweep.

For a local profile, first rebuild with `./gradlew :klause-cli:installJvmDist`, then invoke the installed
CLI with `-e cp --param arms=1 --param bt-arm=conflictDriven --param node-limit=100 -r 3 -s -t 30000`
and the corpus XML path. Set `KLAUSE_CLI_OPTS` to
`-Xmx3g -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -XX:StartFlightRecording=filename=table.jfr,settings=profile`.
Use `jfr print --json --events jdk.ExecutionSample table.jfr` to inspect stacks. Root construction
samples must be separated from pin/learned-clause propagation. Samples without either stack are not
attributed to either phase. Profiling is diagnostic; use the alternating lab runs for timings.

Compare arm elapsed milliseconds at equal completed nodes, failures, root fixings, and learned statistics.
Compare nodes and search progress at equal wall budgets. Portfolio work is charged work, not a count of
candidate evaluations. These experiments compare klause with itself; the reference baseline is incomplete,
so they establish neither solver parity nor oracle-confirmed failures. A timeout without a witness remains
unknown. The table tests check sparse support boundaries, duplicate supports, nested rollback on both
bitset and wide-value paths, and the implications of recorded explanations.
