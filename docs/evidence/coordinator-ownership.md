# State ownership evidence

Concrete scratch aliases and reusable buffers keep lifecycle owners out of numerical
and traversal policy. Indexed vectors retain separate role identities, and run handles
retain one cursor and one cleanup responsibility. The ownership contracts are in
[LP architecture](../lp/architecture.md), [shared search](../solver/search.md) and
[local search](../solver/local-search.md).

The reusable [LP consumer probe](../../klause-bench/scripts/lp-lifecycle/) checks a
21-state bounded push/pop trace against an independent vertex oracle, including
source bounds, exact primals/objectives/lower bounds and closure of every owner.
Four matched workspace, basis, pricing and shared-search comparisons checked 2,688
states. Each arm matched its counterpart across revisions on results and numerical
work. Warm median JVM thread allocations
changed by at most +0.54% in any measured consumer phase.

The [local-search probe](../../klause-bench/scripts/coordinator-lifecycle/) checked
12,800 emitted witnesses across three existing DIMACS fixtures, satisfaction and
Boolean minimization, legacy driving and seven-instruction slices. Within each case, results, charged
work and retained weight/activity digests matched; warm median thread allocations
changed by at most +0.4%.

Independent source checking accepted 212 numeric/Boolean CLI records, including all
60 exact MPS records across five stages. Thirty-two default rounded MPS optima are
excluded from exact-proof acceptance. Boolean and XCSP3 witness checks do not by
themselves certify an unclaimed optimum or refutation.

These bounded comparisons support concrete buffer and lifecycle ownership. They do
not establish whole-CLI speedups, native allocation totals or corpus-wide behavior.
The maintained [benchmark workflow](../../klause-bench/README.md#lp-lifecycle-comparison)
requires pinned installed builds, matching source outcomes/work, warm paired runs
and separate whole-CLI controls. GitHub CI covers integration after ownership rebases; comment and documentation
changes do not alter measured traversal statements.
