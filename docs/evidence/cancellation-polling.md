# Cancellation polling cost and dispatch

Cancellation polling protects resource stops, publication and caller deadlines.
Its [resource contract](../lp/certification.md#resources-and-cancellation)
requires immediate work-metered checks and bounded ordinary-token polling.
Dynamic deadline adapters remain live; ordered compositions retain their
construction-time deadline and first-meter snapshots.

A pinned-main AWS profile at `c82d8f3f7461602340c8dbebffeca2dd0b02db93`
contains 1790 mygetpwnam execution samples: 341 include `Cancellation.invoke`
and 310 include `OrCancellation.invoke`. Caller attribution includes
reconstruction polling (189), LP delegate wrappers (69) and `SearchSession`
(47). These groups overlap; they are attribution evidence rather than additive
CPU costs. Frame removal or inlining does not establish saved execution time.

The [fixed-work source/build check](https://github.com/Eignex/klause/actions/runs/38043602143)
compares `888a7dc594a5681327922c58de7b7ea147df59fc` with
`ce80276bc741d9c366dc0e176bc0ee12dfb59eb0`, differing only in direct dispatch
through owned adapter/composite polling methods and regression tests. One AWS
CP process, default LP, seed 1, work 3000 and five alternating pairs per input
produce identical non-timing counters and unknown outcomes.

| nec-smt input, each `prp-1-46` | Median paired duration reduction |
|---|---:|
| `med/mygetpwnam` | 0.45% |
| `large/handler_sigchld` | -0.57% |
| `large/checkpass` | -0.20% |

These measurements do not establish a useful dispatch speedup. The current
implementation retains its existing dispatch and polling frequency. Regression
coverage protects live deadline rearming and external cancellation through both
public polling methods. Profiles and raw records belong in active external
evidence, separate from this durable decision summary.
