# Cancellation composition, session d929

Issue #2349 reported cancellation polling as a large item in an open-theory profile.
Library-owned OR composites retain an ordered array of leaf predicates instead of
recursively calling other OR composites. The change preserves predicate order and
short-circuiting, cached deadline snapshots and first-meter forwarding. Reconstruction
and refinement space out ordinary token and clock reads using the existing
64-call/4096-work stride. Resource caps remain checked on every charge, work-metered
tokens retain per-charge checks, and certificate returns check cancellation immediately.
Cancelled operations discard their evidence.

## Flattening-only experiment

[Job 819](http://192.168.50.104:8420/?job=819) compared the recursive parent
`f605646989a14a3d71f25a067a94edc3710b9602` with the flattened candidate
`c6154ad68a7ae18ad391f0646367cbabb639126c` on the lab host. It selected
`suite=smtlib-qflia per-family=1000 max=3 seed=1 reference=any` with the three exact
names below. Both arms used `engine=cp processors=1 lp=default timeout=20000 exact=false`,
solver seed 1, three repeats and parallel 1, with alternating arm order. Each CLI
was rebuilt with `:klause-cli:installJvmDist`. The lab queue serialized performance
cases. Runtime settings match: Mac OS X aarch64, `-Xmx3g -XX:+UseSerialGC
-XX:ActiveProcessorCount=1`.

| nec-smt instance, each ending in prp-1-46 | Median theory checks/second, recursive / flat | Median reported solve seconds, recursive / flat |
| --- | --- | --- |
| med/mygetpwnam | 55.024 / 55.033 | 19.971 / 19.921 |
| large/handler_sigchld | 274.994 / 277.672 | 20.287 / 19.931 |
| large/checkpass | 145.850 / 143.676 | 19.891 / 20.120 |

All 18 cases completed normally and reported unknown at their time budgets. They
provide no solved-runtime comparison or proof-verdict validation. Median throughput
changes are approximately 0%, +1% and −1.5%; this small experiment establishes no
clear overall speedup. Time-limited search also changes the amount of search and
the learned clauses reached, so raw work counts are not fixed-work comparisons.
No CPU profile was collected for job 819, and the original 13% profile cannot be treated
as a measured saving from this change.

[Job 816](http://192.168.50.104:8420/?job=816) used the same commits and runtime settings
but omitted `per-family=1000`. The catalog's default sampling selected only
`med/mygetpwnam`, giving six cases rather than the requested eighteen. All returned
unknown. Median theory throughput was 55.472 / 55.324 checks/second, recursive / flat;
it also establishes no clear gain. Job 819 supplies the explicit large-case coverage.

Specs, raw files, case JSON and build provenance are preserved in the lab job Files
pages and `/home/rasmus/Workspaces/lp-evidence/session-lp-d929/`. The record policy is
`reported-result-v1`. There were no feasible witnesses to check independently in
these experiments. Regression tests instead cover immediate external predicates,
nested ordering and short-circuiting, dynamic deadline adapters, deadline expiry,
first-meter work-budget exhaustion and AND semantics. Targeted cancellation tests
and KDoc passed; the full local `check lintDocs` gate was skipped in favor of GitHub CI.

## Strided certificate polling

[Job 830](http://192.168.50.104:8420/?job=830) compares the per-charge stack
`8ffde6abb0774b98b6cb9ea3d2a36d2d33f5df42` with strided certificate polling
`a3c69234501ce06c937c826c9c9d6eb5e591f831`. It uses the same three named instances,
CP, one processor, seed 1, `lp=default exact=false timeout=20000`, five repeats and
alternating arms. All 30 cases run serially on one AWS c7i.2xlarge instance,
`i-0b0c05d22a9e95726`, with Linux amd64 and the same JVM options given above.
Both CLI distributions are rebuilt with `:klause-cli:installJvmDist`.

| nec-smt instance, each ending in prp-1-46 | Median theory checks/second, per-charge / strided | Ratio |
| --- | --- | --- |
| med/mygetpwnam | 18.023 / 137.241 | 7.61x |
| large/handler_sigchld | 131.232 / 171.183 | 1.30x |
| large/checkpass | 50.397 / 72.460 | 1.44x |

All cases report unknown at their time budgets. These gains measure search progress,
not time to a solved verdict. Changing deadline check cadence also changes which
late operations finish; this comparison does not hold total search work fixed.

[Profile job 825](http://192.168.50.104:8420/?job=825) on the per-charge stack and
[profile job 833](http://192.168.50.104:8420/?job=833) on the candidate each capture
one 20-second CLI JVM per named instance on separate c7i.2xlarge instances.
Inclusive `Cancellation.invoke` samples fall from 1622/1909 (85.0%) to
324/1781 (18.2%) on `med/mygetpwnam`, and from 315/1761 (17.9%) to
10/1763 (0.6%) on `large/checkpass`. The per-charge stacks name
`ReconstructionMeter.step`, `RefinementMeter.charge`, and the theory LP cancellation
adapter. JFR samples are statistical and cover different amounts of search work;
the percentages are not absolute CPU-time savings. Profile timings are excluded
from the uninstrumented comparison.

## Matched search work

[Job 834](http://192.168.50.104:8420/?job=834) uses the same commits and three instances,
with `param.node-limit=1000`, a 120-second safety deadline, seed 1, three alternating
repeats, and one CLI at a time on c7i.2xlarge instance `i-035821f57696ec7e2`.
This existing CLI option caps open-theory checks and decisions together. All 18 cases
stop at the work limit, before the safety deadline, with exactly 1000 search-work
units. Each pair has the same theory-check count and deterministic solver counters.

| nec-smt instance, each ending in prp-1-46 | Theory checks in each run | Median solve seconds, per-charge / strided | Reduction |
| --- | --- | --- | --- |
| med/mygetpwnam | 529 | 32.139 / 5.857 | 81.8% |
| large/handler_sigchld | 514 | 6.186 / 5.239 | 15.3% |
| large/checkpass | 526 | 12.817 / 10.906 | 14.9% |

Every paired candidate run is faster than its control. The cases report unknown
because search work is capped; these results establish a runtime improvement for
these matched search prefixes, not a gain in solved instances or a whole-corpus
speedup. Work-metered cancellation still uses the immediate path and is covered by
regression tests rather than claimed as a performance improvement here.

## MPS outcomes and witnesses

[Job 832](http://192.168.50.104:8420/?job=832) compares the same commits on the lab
host, using `suite=miplib3 name=blend2,egout,flugpl max=3 reference=any`, three
alternating repeats, and the same CP, seed, timeout and runtime settings as job 819.
All 18 cases finish normally. Both arms report unknown on `blend2`, objective
606.0797 without an optimality proof on `egout`, and proven objective 1201500 on
`flugpl`. Median reported solve seconds are 19.854/19.854, 19.909/19.909, and
0.640/0.641 respectively; this supplies no MPS runtime improvement claim.

All twelve feasible source witnesses pass the existing independent MPS checker,
with maximum relative constraint violation 2.22e-15 on `egout` and zero on
`flugpl`. This checks feasible assignments, not the optimum proofs. Job 831 was
cancelled after a suite-name selection error, before any solver cases ran; job 832
uses the corrected spec.

Specs, complete case records, CLI/runtime hashes, raw solver output, JFR files and
independent witness results are preserved in these jobs and the session evidence
directory. Each uninstrumented arm has one build fingerprint and uses
`reported-result-v1`. The production code is unchanged by subsequent cancellation
fixture adjustments. The 110 targeted LP/theory tests and 50 point/replay tests pass;
the full local `check lintDocs` gate is skipped in favor of GitHub CI.
