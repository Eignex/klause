# Table search throughput

The integrated comparison uses main baseline e4dc3458d8f8cae1e3321987132bab43b155c68c and
candidate 84800b5124888eb4c878d9b5658dafae82487b9f. Formatting and documentation commits preserve the
candidate's behavior. Main's lazy per-column explanations are present in both revisions.
The [historical comparison](historical/README.md) predates those explanations and is kept separately.

## Paired experiments

Submit with klause-lab's `deploy/lab run <file>`. Both specifications pin one backtrack arm,
one processor, seed 3, and three alternating repeats on the same lab Mac, with JDK 25,
Serial GC, and a 3 GB heap. They select HSP-table-12407_c23, HSP-table-13408_c23, and
HSP-table-14409_c23, plus magic-series-tiny, sum-opt-tiny, and graph-coloring-tiny as fast
XCSP3 file controls.

- [100-node satOptimized cases](http://192.168.50.104:8420/jobs/574), reproduced by `nodes.json`,
  allow 120 seconds so the baseline can complete the same search prefix.
- [10-second cases for both recipes](http://192.168.50.104:8420/jobs/575), reproduced by `time.json`,
  measure completed nodes and handled conflicts for satOptimized and conflictDriven.

Compare solve time only for fixed-node pairs that complete the cap and match nodes, failures,
restarts, root fixings, and peak depth. Solve time includes root setup. Timed runs measure search
progress; conflictDriven can spend substantial time resolving conflicts between decision nodes,
so its failure count matters alongside completed nodes. Longer trajectories may diverge and
are not an isolated per-node speed ratio. The tiny controls check verdicts, objectives, and search
counters; their millisecond timing differences do not demonstrate a general speedup.

Median solve seconds at 100 nodes for satOptimized:

| HSP instance | Baseline | Candidate | Reduction |
| --- | ---: | ---: | ---: |
| 12407_c23 | 29.416 | 10.590 | 64.0% |
| 13408_c23 | 17.756 | 7.165 | 59.6% |
| 14409_c23 | 19.090 | 6.383 | 66.6% |

All nine HSP pairs are faster and match the search counters listed above. Each remains unknown
at the node cap. The three file controls match verdicts, objectives, and search counters, with
median times of 15–35 ms in both revisions. All 36 records load successfully.

The abandoned integration jobs 564 and 565 were cancelled after profiling identified the
explanation bottleneck; they are not evidence for this candidate. Portfolio work is charged work,
not a candidate-evaluation count. The reference baseline is incomplete; these experiments compare
klause with itself and claim neither reference parity nor an oracle-confirmed failure.

## Profile and implementation

Before editing the integrated table path, local JFR profiles pinned each recipe on
HSP-table-14409_c23 with a 100-node cap and 30-second budget. TablePropagator.supportLoss
appeared in 1857/2936 execution samples for satOptimized and 1786/2876 for conflictDriven.
Root PropagationSession construction accounted for 63 and 66 samples respectively. The
remaining stacks include propagation and unattributed frames. Leaf samples concentrated on
historical domain reads, carve-history scans, and cell checks. These capped diagnostic recordings
have different completed search prefixes; use the alternating lab cases for timings.

Explanation-local caches reuse historical domains, carve positions, and materialized literals.
Point cells use membership checks; ground supports use a value set rather than sorting repeated
single-value intervals. Sparse root members are walked when fewer than the domain's holes,
which avoids span-sized walks through unconditional root holes. The caches live for one synchronous
explanation build and never cross propagation or undo. Historical carve positions still determine
whether a hole existed when the deduction was recorded, and premise selection retains its order.

Support gathering stops for a ground column after all live values are covered. Short-support
intervals fill bitsets by words. All tuples are still checked for feasibility, and the reversible
live prefix is written once per completed sweep, including empty-prefix conflicts. Per-fire scratch
state is rebuilt, so sibling branches restore support filtering without a persistent support cache.
Tests cover duplicate supports, sparse word boundaries, nested and conflict rollback on both
value paths, implication of explanations, and delayed materialization across later carves and
sibling branches. An existing enumeration test over the sparse domain {0, 5e9} also checks the
span-independent explanation path.

Rebuild with `./gradlew :klause-cli:installJvmDist`, then invoke the installed CLI with
`-e cp --param arms=1 --param bt-arm=conflictDriven --param node-limit=100 -r 3 -s -t 30000`
and the corpus XML path. Set `KLAUSE_CLI_OPTS` to
`-Xmx3g -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -XX:StartFlightRecording=filename=table.jfr,settings=profile`.
Use JDK 25's `jfr print --json --events jdk.ExecutionSample table.jfr` to inspect stacks and
separate root construction, table explanations, and propagation before interpreting the profile.
