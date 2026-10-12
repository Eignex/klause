# State ownership evidence

The bounded comparisons support retaining concrete buffer owners, separate indexed
vector roles and one cursor/cleanup owner per run. [LP architecture](../lp/architecture.md),
[search](../solver/search.md) and [local search](../solver/local-search.md) own the contracts.

The [LP consumer probe](../../klause-bench/scripts/lp-lifecycle/) checked 2,688 states
across matched workspace, basis, pricing and traversal comparisons. An independent
vertex oracle checked source bounds, exact values/objectives/bounds and owner closure.
Results and numerical work matched; warm median JVM thread allocations changed by
at most +0.54% per measured consumer phase.

The [local-search probe](../../klause-bench/scripts/coordinator-lifecycle/) checked
12,800 witnesses across three DIMACS fixtures, satisfaction/minimization and legacy
versus seven-instruction slices. Results, charged work and retained-state digests
matched; warm median thread allocations changed by at most +0.4%.

Independent source checks accepted 212 numeric/Boolean CLI records, including 60
exact MPS records. Thirty-two rounded default-MPS optima were excluded from exact
proof acceptance. Witness checks alone do not certify optimality or refutation.

These results establish neither whole-CLI speedups nor native allocation totals or
corpus-wide behavior. [Benchmark workflows](../../klause-bench/README.md#lp-lifecycle-comparison)
own pinned builds, paired measurements and whole-CLI controls.
