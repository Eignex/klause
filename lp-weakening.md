# Exact explanation weakening

Wave 7.1 adoption is deferred. The bounded implementation was built and independently reviewed at
`f9782b4ec781de2172aa5bc40f4ecb785928b0ad`, from base
`8d2e9c5ce1e4c58b2a23b97edab9f03aba42a18f`, then removed after the frozen measurements rejected it.
There is no disabled weakening framework or new runtime option.

The experiment supported source Farkas and crossed-bound conflicts through `LpPropagator`, including
rational and strict bounds. It spent one exact surplus across active weaker witnesses and retained
row, fixing and derived premises. Legacy CP explanations, integer-only refutations and unmapped
auxiliary conflicts were outside its supported scope.

Eight directed LP/SearchSession workloads ran 100 times per batch, with one warmup per arm and three
paired repetitions on a busy host. Each enabled batch had 700 eligible attempts: 500 weakened and
200 without benefit; another 100 were unsupported. It skipped 100 unavailable candidates. All 800
learned clauses propagated after an actual backjump in both arms; the sum of destination levels fell
from 2,000 to 1,300. LP solve work remained 7,800 operations. Literal count was not the selection goal.

| Median batch measurement | Off | On |
| --- | ---: | ---: |
| Complete fixture lifecycle, including source validation | 65.63 ms | 95.55 ms |
| Explanation construction | 0.83 ms | 12.44 ms |
| Additional arithmetic and scan work | 0 | 47,600 |

The 45.6% lifecycle slowdown exceeds the predeclared 10% limit. All three pairs were slower with
weakening. These directed fixtures establish capability and backjump behavior; they do not establish
broad solve performance or Borda improvement. Premise traversal is included in time, not the arithmetic
work counter. Legacy CP coverage controls do not measure every production explanation attempt.

All 24 dedicated correctness tests passed, but two integration tests took 745 ms and 453 ms, exceeding
the 300 ms test limit. Eleven existing CP/SMT source checks passed in both versions. Rebuilt CLI smoke
cases retained a valid wide-span integer assignment and the independently checked CP optimum of 10.
Failures, test timings, exact source checks, the frozen contract, implementation patch/bundle and runner
are preserved in `/home/rasmus/Workspaces/lp-evidence/session-7.1/`.

Wave 7.2 can use the existing bound trail and recursively expanded premises without a dependency on
weakening. Reconsider weakening only with cheaper bounded candidate selection and new predeclared
consumer evidence; retain shared-surplus, strictness, source-authority and publication checks.
