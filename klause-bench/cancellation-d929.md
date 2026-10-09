# Cancellation composition, session d929

Issue #2349 reported cancellation polling as a large item in an open-theory profile.
Library-owned OR composites retain an ordered array of leaf predicates instead of
recursively calling other OR composites. The change preserves predicate order and
short-circuiting, cached deadline snapshots, first-meter forwarding and every existing
polling point. Deadline predicates still read their clocks; this change does not
coarsen polling or claim to eliminate the entire reported cancellation cost.

## Paired experiment

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
No new CPU profile was collected, and the original 13% profile cannot be treated
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
