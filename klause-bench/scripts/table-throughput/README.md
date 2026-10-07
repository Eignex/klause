# Table search throughput

The klause-lab experiments pin one satOptimized or conflictDriven arm, one processor, seed 3,
and three alternating repeats on the same lab Mac. `nodes.json` allows 100 nodes and 30 seconds;
`time.json` allows 10 seconds. Both select HSP-table-12407_c23, HSP-table-13408_c23, and
HSP-table-14409_c23, plus magic-series-tiny, sum-opt-tiny, and graph-coloring-tiny as fast XCSP3
file controls. Submit with klause-lab's `deploy/lab run <file>`.

- Baseline: da388ebcd1c20580bfe061c7a9811829e8cb2efc.
- Candidate: 234a97c76acec97e2fa7b89972ddcddc284f20e9.
- [100-node HSP cases](http://192.168.50.104:8420/jobs/549).
- [10-second HSP cases and file controls](http://192.168.50.104:8420/jobs/554).
- [100-node file controls](http://192.168.50.104:8420/jobs/555), reproduced by `controls.json`.

The candidate's table implementation matches the PR; subsequent commits add tests, evidence, and
formatting. Job 549's original in-code controls cannot load in subprocess solves: its 36 control
records are harness errors and excluded. Job 555 replaces them with file-based controls; the saved
`nodes.json` combines the valid selections. Every HSP record and replacement control loaded successfully.

## Results

Median solve seconds at exactly 100 nodes:

| HSP instance | Arm | Baseline | Candidate | Reduction |
| --- | --- | ---: | ---: | ---: |
| 12407_c23 | satOptimized | 0.980 | 0.749 | 23.6% |
| 12407_c23 | conflictDriven | 1.277 | 0.846 | 33.8% |
| 13408_c23 | satOptimized | 0.699 | 0.492 | 29.6% |
| 13408_c23 | conflictDriven | 6.721 | 4.951 | 26.3% |
| 14409_c23 | satOptimized | 1.850 | 1.390 | 24.9% |
| 14409_c23 | conflictDriven | 5.040 | 3.704 | 26.5% |

All 18 paired HSP runs were faster (geometric mean time ratio 0.734). Nodes, failures, restarts,
root fixings, and peak depth match within every pair. These runs remain unknown at the node cap.
The three file controls preserve their nodes, verdicts, and objectives; their median times are
15–39 ms, so their small timing differences are not evidence of a general speedup.

Median completed nodes at 10 seconds:

| HSP instance | Arm | Baseline | Candidate |
| --- | --- | ---: | ---: |
| 12407_c23 | satOptimized | 1360 | 10659 |
| 12407_c23 | conflictDriven | 368 | 458 |
| 13408_c23 | satOptimized | 979 | 1698 |
| 13408_c23 | conflictDriven | 242 | 362 |
| 14409_c23 | satOptimized | 666 | 1453 |
| 14409_c23 | conflictDriven | 433 | 569 |

All six pairs make more search progress. The candidate's satOptimized arm finds objective 356 on
12407_c23 in every repeat (first witness at 8.30–8.41 seconds); its baseline finds no witness.
Other HSP arms remain unknown. Longer runs follow different trajectories, so these node ratios
measure search progress rather than isolating a per-node cost ratio. The time-budget file controls
also preserve their verdicts and objectives. No reference parity or oracle-confirmed failure is claimed.

## Profile and reproduction

Before changing the propagator, local JFR CPU profiles pinned each arm at 100 nodes on
HSP-table-14409_c23 using JDK 25, Serial GC, a 3 GB heap, and ActiveProcessorCount=1.
Of samples with a PropagationSession pin or learned-clause stack, TablePropagator.propagateBitset
appeared in 165/182 for satOptimized and 528/590 for conflictDriven. Root session construction
accounted for 24 samples in each recording (310 and 797 samples total). Leaf samples concentrated
on support gathering; reversible tuple removals also appeared. The HSP relations use ground cells,
so stopping completed columns matters separately from filling short-support intervals by words.
The live sparse-set prefix is trailed once per completed sweep.

First rebuild with `./gradlew :klause-cli:installJvmDist`, then invoke the installed CLI with
`-e cp --param arms=1 --param bt-arm=conflictDriven --param node-limit=100 -r 3 -s -t 30000`
and the corpus XML path. Set `KLAUSE_CLI_OPTS` to
`-Xmx3g -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -XX:StartFlightRecording=filename=table.jfr,settings=profile`.
Use `jfr print --json --events jdk.ExecutionSample table.jfr` to inspect stacks. Root construction
samples are separated from pin/learned-clause propagation; other stacks are not attributed to either
phase. Profiling is diagnostic; use the alternating lab runs for timings.

Portfolio work is charged work, not a candidate-evaluation count. The reference baseline is incomplete;
these measurements compare klause with itself. The table tests cover sparse support boundaries,
duplicate supports, nested rollback and empty-prefix conflict rollback on bitset and wide-value paths,
and the implications of recorded explanations. The support-completion scratch state is recomputed on
every fire, and tuple feasibility and deduction antecedents retain their existing semantics.
