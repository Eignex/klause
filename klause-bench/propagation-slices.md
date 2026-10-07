# Propagation slice measurements

Charging propagation visits and leaf LP work reduces the portfolio monopoly of expensive backtracking nodes. It changes scheduling rather than the speed of a propagation kernel. Atomic construction, fixpoints and LP calls can still overrun a slice, so [#2221](https://github.com/Eignex/klause/issues/2221) remains open. The scheduler slice sizes from [#1748](https://github.com/Eignex/klause/issues/1748) are unchanged.

## Reproduction and measurement

Baseline: `da388ebcd1c20580bfe061c7a9811829e8cb2efc`.
Measured candidate: `16644a3be424380e395ffa88e66f73580b71bb41`.
The later `ef6b99312292b8d2c6aad71e4afd7b0b8fe65451` adds a reset regression and clears a pending decision during `SearchRun.reset()`, which these CLI cases do not invoke. Final cleanup appends the public statistics fields to preserve existing parameter and destructuring order, refreshes ABI snapshots, returns constructor refutations without waiting for slice allowance, and applies formatting; it does not change the measured budget or search behavior.

The [portfolio specification](propagation-slices/portfolio.json) runs six expensive targets and four controls with a 10 s CLI deadline, one processor, seed 3 and three repeats per revision. The [fixed control specification](propagation-slices/fixed-controls.json) uses `engine=fixed`, a 5,000-decision limit and a 30 s deadline on the same four controls. Each pair uses rebuilt `:klause-cli:installJvmDist` binaries on the lab Mac; repeat order alternates revisions. The lab runs two subprocesses concurrently. Times include process warmup and host variation; they are descriptive ranges, not an uncontended microbenchmark.

Queue with `klause-lab/deploy/lab run <specification>`. Raw cases and CLI counters are available from [portfolio job 556](http://192.168.50.104:8420/jobs/556) and [fixed controls job 557](http://192.168.50.104:8420/jobs/557), under `/experiments/<id>/cases` and `/jobs/<id>/files`.

`propagationWork` counts actual dispatches, literal queries, watcher and level visits, and linear term/reason inspections. It is monotonic across undo. `rootPropagationWork` isolates the session constructor's root fixpoint; later seeds and restarts belong to the remaining work. `propagationMs` times fixpoints with one clock pair each, and `rootPropagationMs` isolates their constructor portion. Some counted reason/level scans occur outside those timers, so work divided by fixpoint time is only a coarse rate. Other expensive factor internals are not fully metered.

Each arm reports total work, total milliseconds, longest segment (`maxMs`) and handle construction (`initMs`). Construction is included in elapsed segments and charged once to the work ledger; excess work is repaid by later slices. These counters separate constructor cost from later search but do not isolate frontend parsing or upstream presolve. Baseline CLI output has total arm time and segment count but no longest-segment or initialization counter.

## Conversion rate

The exploratory [paired job 551](http://192.168.50.104:8420/jobs/551) used 1,000 propagation visits per slice node. The ratio of candidate non-root visits per fixpoint second to baseline search nodes per solve second was 7,135 for contiguity, 5,186 for fixnet6, 2,687 for blend2 and 2,192 for knapsack. Their median, 3,936, gives the retained rounded rate of 4,000. This coarse conversion balances slice work, with the existing LP rate of 600 operations per node retained. Decision and move limits still count their original events.

## Portfolio results

All 60 cases completed. Ranges cover the three repeats. Coverage counts arms with at least one segment; declined arms can account for fewer than seven. Longest segments below include construction and refer to backtracking arms only.

| Target | Baseline nodes | Candidate nodes | Arm coverage baseline → candidate | Candidate longest backtracking segment |
|---|---:|---:|---:|---:|
| Coprime-14 | 20 | 24 | 2 → 7 | 1.39 s |
| IHTC-i04 | 2,034–3,268 | 1,024–2,364 | 7 → 7 | 0.34–0.46 s |
| arithmetic-target 8657 | 1,600–2,291 | 1,195–1,826 | 6 → 6 | 0.98–1.56 s |
| IHTC-i16 | 32 | 2 | 4 → 5 | 1.19–1.38 s |
| concert 200 20 | 85–102 | 68–170 | 7 → 7 | 1.01–1.31 s |
| misc04inf | 107 | 166–171 | 6 → 6 | 1.98–2.29 s |

Coprime baseline gave each of its two backtracking arms one segment: the second lasted 6.34–6.44 s. Candidate backtracking ran seven segments across those arms, allowing all seven portfolio arms to start. Its 193 million propagation visits include only 0.53 million root visits; root fixpoints took 3–7 ms of 4.62–4.65 s of fixpoint time. Combined backtracking construction took 82–97 ms. The changed allocation reaches more arms without making the kernel itself faster.

IHTC-i16 is dominated by startup: 41.1 million of 50.3 million visits belong to constructor fixpoints. Root fixpoint time is 682–800 ms of 723–869 ms total; combined backtracking construction takes 2.10–2.47 s. Charging that work gives another local-search arm time but leaves only two backtracking decisions. This is a fairness tradeoff, with an atomic startup cost still exceeding a nominal short slice.

IHTC-i04 records 420–1,036 million visits and 1.36–2.55 s of fixpoints, against only 4.05 million root visits and 85–130 ms of root fixpoints. Arithmetic 8657 records 973–1,033 million visits and 3.95–4.83 s of fixpoints, against 0.39 million root visits and 19–26 ms of root fixpoints. Wide propagation and reason scans dominate later work on these targets. The counters also include scans outside fixpoints, so these totals do not attribute all elapsed search time to propagation.

misc04inf spends only 3–5 ms in fixpoints, with about 15–18 thousand propagation visits. Candidate leaf LP work is 1.03–1.08 billion operations. Charging those completions increases allocation to the LP arms, but a single solve still takes about two seconds. This target does not establish a propagation bottleneck.

Coprime, both IHTC cases, arithmetic 8657 and misc04inf remain unresolved in both revisions. Concert is maximization: baseline emits feasible incumbents in all three repeats (1,054,093–1,062,839), candidate in two (both 1,062,839). Both revisions can exceed the deadline in atomic local-search segments; one candidate best arrives at 11.128 s of search time. These outcomes do not establish a gain at a matched elapsed budget.

## Fixed decision controls

All 24 cases completed with matching nodes, failures, solutions, restarts, learned clauses and LP work per source across revisions. Contiguity and knapsack have solver-reported optima 10 and 54,500 respectively; fixnet6 and blend2 remain unresolved at the 5,000-decision cap. No external optimum comparison is part of these experiments.

| Control | Nodes in both revisions | Baseline solve time | Candidate solve time |
|---|---:|---:|---:|
| contiguity mip2 | 789 | 107–109 ms | 106–146 ms |
| fixnet6 | 5,000 | 400–414 ms | 403–427 ms |
| blend2 | 5,000 | 673–694 ms | 692–728 ms |
| unbounded integer knapsack | 11 | 22–25 ms | 25–28 ms |

The additional [twelve contiguity pairs in job 563](http://192.168.50.104:8420/jobs/563), using the [overhead specification](propagation-slices/overhead.json), preserve all 789 decisions and the same proof counters. Baseline median solve time is 133 ms (range 99–185); candidate median is 149 ms (113–209), about 12% slower. Mean times are 138.5 and 153.6 ms. Counting four million visits has a measurable cost on this cheap case, with substantial process timing variation. The change does not establish zero-cost instrumentation or a kernel speedup.

The portfolio controls also expose scheduling cost: fixnet6 averages about 73 thousand candidate nodes versus 140 thousand baseline nodes in the same deadline, and blend2 about 201 thousand versus 218 thousand. Leaf LP charging redirects time among the six arms, so these node differences are not an instrumentation-only throughput estimate. Both stay unresolved. Contiguity proves objective 10 in both revisions, with candidate solve time 318–464 ms versus 314–409 ms; knapsack proves 54,500 with 237–241 ms versus 231–239 ms. Broader solution-quality gains remain unestablished.

## Pause safety and remaining limits

Slices yield before consuming an alternative or after a completed node. A failed sibling can yield without skipping its successor, and a reset discards the pending alternative. A constructor refutation returns immediately even when its work consumes the slice allowance. The last rejected root leaf finishes as exhausted even if its completed work expires the slice. Regression tests also cover monotonic work across undo, fractional charges, overspend repayment, startup work charged once, standalone leaf LP work, and identical optimum/decision/work counts for sliced and whole searches. The slowest added JVM test took 128 ms in the targeted run.

Propagation fixpoints continue to finish atomically under the slice budget; solve-wide cancellation remains available within propagation. Constructor/root work, one expensive factor, one LP solve, and a value-probe batch can still monopolize elapsed time. Local-search segments have their own uninterruptible work. Some nonlinear factor internals are absent from the visit meter. The 4,000-visit conversion is a measured scheduling scale, not a wall-clock guarantee; later kernel work or cooperative inner-loop cancellation needs separate evidence.
