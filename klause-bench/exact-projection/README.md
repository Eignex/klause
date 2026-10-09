# Current-main exact arithmetic investigation

Partial work toward [#2348](https://github.com/Eignex/klause/issues/2348).
The reproducer is [PR #2373](https://github.com/Eignex/klause/pull/2373).
The stack continues with [numerical PR #2379](https://github.com/Eignex/klause/pull/2379)
and [validation PR #2383](https://github.com/Eignex/klause/pull/2383).
The baseline is `5471419aa8c832ee1065468a6861bb203a1ff8c7`, after the cancellation
polling work in [#2363](https://github.com/Eignex/klause/pull/2363).

## Reproduction

From the repository root, with a sibling `klause-lab` checkout:

```sh
../klause-lab/deploy/lab run klause-bench/exact-projection/profile-main.json
../klause-lab/deploy/lab run klause-bench/exact-projection/work-main.json
../klause-lab/deploy/lab fetch 851 /tmp/exact-profile-851
python3 klause-bench/exact-projection/profile.py /tmp/exact-profile-851
curl -fsS http://192.168.50.104:8420/experiments/JOB/cases > /tmp/exact-cases.json
python3 klause-bench/exact-projection/compare.py /tmp/exact-cases.json
```

The comparator expects paired arms named `main` and `candidate`. It reports every
outcome or non-timing solver-counter difference rather than assuming a node limit
alone fixes work. Timing, rate and elapsed-duration counters are excluded explicitly.

[Profile job 851](http://192.168.50.104:8420/?job=851) and
[work-baseline job 854](http://192.168.50.104:8420/?job=854) use dedicated AWS
c7i.2xlarge instances, one case at a time, CP, one processor and seed 1.
The three named nec-smt inputs are checkpass, handler_sigchld and mygetpwnam, each
`prp-1-46`. Selection sets `per-family=1000` so the controls survive family sampling.
Both jobs pin the commit rather than a moving branch. The lab builds
`:klause-cli:installJvmDist` remotely and retains CLI/runtime build fingerprints,
selection, commands, JVM options and case records in the job Files pages.

JFR records the whole CLI JVM at a 20-second solve budget. Its samples include
startup and are statistical; inclusive symbols overlap and cannot be added as
exclusive costs. Profile timings are excluded from runtime comparisons.
The uninstrumented baseline caps search work at 1000 with a 120-second safety
deadline. These runs cannot establish a solved-runtime improvement if they stop
unknown at the cap. Paired measurements must use the same instance and executed
build identities; the separate baseline job is a characterization, not a timing
control for a later candidate.

No local Gradle build, solve, test, lint or documentation gate is run for this stack.
GitHub CI supplies the build/test/lint/documentation gates. Raw-record analysis is
separate from experiments and does not execute a solver.

## Current-main profile

Job 851 completed all three cases on instance `i-0481ad434ce5c125f`. The retained
[summary](profile-main-summary.json) includes the executed build fingerprints and
SHA-256 of each JFR file. All three outcomes are unknown at the time budget.

| Inclusive symbol | checkpass, 1793 samples | handler_sigchld, 1682 | mygetpwnam, 1764 |
| --- | ---: | ---: | ---: |
| BigFraction.of | 113 (6.3%) | 88 (5.2%) | 75 (4.3%) |
| withPublishedBounds | 86 (4.8%) | 9 (0.5%) | 13 (0.7%) |
| LiveQfLraSystem.install | 73 (4.1%) | 20 (1.2%) | 9 (0.5%) |
| LpExactState.projectScalars | 2 (0.1%) | 1 (0.1%) | 0 |
| Cancellation.invoke | 18 (1.0%) | 16 (1.0%) | 317 (18.0%) |

Rational construction is the largest remaining named numerical item on checkpass.
Its factory computes a GCD even for denominator one and divides numerator and
denominator even when the GCD is one. IEEE conversion also sends its already
reduced pair through that factory. These are candidates for a focused arithmetic
change; this profile supplies no absolute CPU saving or end-to-end speedup claim.
The residual cancellation share on mygetpwnam remains outside this change.

## Paired candidate campaigns

The numerical change is [PR #2379](https://github.com/Eignex/klause/pull/2379),
stacked on the reproducer. Its executed candidate revision is
`9aa1cf5d1a` (the full immutable revision is retained in each specification).

- [Fixed-work job 865](http://192.168.50.104:8420/?job=865): five alternating repetitions,
  1000 search work units and a 120-second safety cap, using [paired-work.json](paired-work.json).
- [Deadline job 866](http://192.168.50.104:8420/?job=866): three alternating repetitions,
  20 seconds, using [paired-deadline.json](paired-deadline.json).
- [Controls job 867](http://192.168.50.104:8420/?job=867): MPS and SMT core plus
  blend2/egout/flugpl, default and exact policies, two repetitions, using [controls.json](controls.json).
- [Profile job 868](http://192.168.50.104:8420/?job=868): separate candidate JFR cases,
  using [profile-candidate.json](profile-candidate.json).

All specify `host=aws`, `machines=1`, `parallel=1`, the same hardware, heap and
processor policy as the baseline. The jobs retain exact commands and fingerprints;
JFR fingerprints include their per-case recording paths and differ even for one build.
Jobs 859–862 were cancelled before solver cases when a factory-helper visibility
problem was corrected. They supply no measurements. AWS capacity delays are outside
measured solver time.

Job 854's uninstrumented characterization completed at work 1000, with 529/514/526
theory checks on mygetpwnam/handler_sigchld/checkpass respectively. Reported solve
seconds are 6.127/5.368/11.871. This single repetition supplies no paired speedup
estimate. Its build fingerprint is
`c5f3896bdc0ec8fd17986a96333426c23c7369322641a064d6e0e9fb8f35e566`.

The SMT-LIB inputs come from the catalog's immutable Zenodo release 15493090,
`QF_LIA.tar.zst`, under `non-incremental/QF_LIA/nec-smt/`. MPS core and SMT core
are repository fixtures at the executed revisions. MIPLIB controls come from the
catalog's MIPLIB 3 archive. The lab records names and source commands rather than
per-input hashes; this provenance limitation is retained rather than treating local
corpus hashes as verified identities of remote inputs.

## Fixed-work result

Job 865 completed 30 uninstrumented cases on `i-01dfcdbf992849ab5`. Every pair has
identical outcomes and every non-timing solver counter, including exactly 1000 work
units. All stop unknown before the safety deadline. The [summary](fixed-work-summary.json)
retains counters, canonical counter hashes and individual times; [provenance](fixed-work-provenance.json)
retains each executed revision, CLI command, runtime policy and solver/runtime binary hashes.

| Input, ending in prp-1-46 | Theory checks | Main median seconds | Candidate median seconds | Reduction | Faster pairs |
| --- | ---: | ---: | ---: | ---: | ---: |
| med/mygetpwnam | 529 | 5.616 | 5.372 | 4.3% | 5/5 |
| large/handler_sigchld | 514 | 5.259 | 4.957 | 5.7% | 5/5 |
| large/checkpass | 526 | 10.456 | 9.982 | 4.5% | 4/5 |

This is a modest improvement for these three deterministic search prefixes on one
machine, not a whole-corpus or solved-runtime claim. One checkpass pair is slower;
five repetitions do not establish a universal speedup. The implementation is the
same after the later test-only destructuring correction.

Job 868's [candidate profile](profile-candidate-summary.json) has 1/1786 checkpass
samples containing `BigFraction.of`, versus 113/1793 on main. Handler has 10/1772
versus 88/1682; mygetpwnam has zero versus 75/1764. The factory's fast paths are
small enough for different inlining, so disappearance of its frame is not a measured
absolute CPU saving. These independent profile runs cover different amounts of
deadline-limited work and contribute no timing samples to the table above.

## Independent witness evidence

[Job 871](http://192.168.50.104:8420/?job=871), specified in [witnesses.json](witnesses.json),
uses the same result-recorder adapter on main (`8647eb38328c77a44c2fe28107e0e087e0b76a21`)
and the numerical candidate (`c4df1fc9d05bfee5cd145fb0d3c9069858c8be9e`). The adapter
preserves the final MPS `v` line in durable result JSON without changing verdict
credit, the solver or its stopping policy. These supplementary runs are outside
the timing comparison. The baseline adapter branch is an experiment reference;
its complete proposed diff is included in the validation PR.

Ordinary AWS runs export result JSON, not successful CLI stdout. The on-instance
raw `.out` files are not exported by the lab, so the first campaigns cannot supply
independent primal checks. The supplementary assignments are checked on GitHub CI
by [check_witnesses.py](check_witnesses.py), reusing the source MPS checker from
`lp-evidence/session-stage-c-exact-duals/check_witness.py`. It parses the original
source independently, checks every row/bound and integer marker to relative 1e-7,
rejects nonfinite values and requires the source's recorded SHA-256. Feasibility
checking is not independent optimality or infeasibility certification.

[The GitHub CI witness check](https://github.com/Eignex/klause/actions/runs/37991052614)
passes every one of the 18 feasible cases (seven distinct printed assignments).
The archived [results](witness-checks.json) show maximum relative violation 2.22e-15
for egout and zero for the remaining witnesses. The checker fetched MIPLIB sources
from the catalog's original archive and verified their pinned hashes; vendored
models were checked against the repository files. The collected source identities
are independent check targets, not asserted hashes of the AWS input files.

## Deadline and shared numerical controls

Job 866 completed all 18 deadline cases on `i-0b9a65fedba4888fa`. All remain
unknown at 20 seconds. The [summary](job-866-summary.json) and
[provenance](job-866-provenance.json) retain outcomes, medians, fingerprints and commands.

| Input, ending in prp-1-46 | Main median theory checks/second | Candidate median theory checks/second |
| --- | ---: | ---: |
| med/mygetpwnam | 102.302 | 128.864 |
| large/handler_sigchld | 115.656 | 151.268 |
| large/checkpass | 59.072 | 64.836 |

These runs reach different search states and amounts of work. Their larger progress
ratios do not replace the matched-prefix timing result or demonstrate a verdict gain.

Job 867 completed 104 default/exact-policy cases over four MPS core fixtures, six
SMT core fixtures, and blend2/egout/flugpl. Every paired outcome and objective matches.
The [summary](job-867-summary.json) and [provenance](job-867-provenance.json) preserve
each policy's results. Blend-tiny reports proven objective 9, flugpl reports proven
1201500, and default-policy egout reports unproven 606.0797. Exact-policy egout
declines on both builds because its source column bound `F....001` differs from the
lowered bound; this is four unsupported cases, not an exact proof check. Blend2
remains unknown. No MPS runtime improvement is claimed.

To regenerate the retained analyses from exported records:

```sh
curl -fsS http://192.168.50.104:8420/experiments/865/cases > /tmp/work-cases.json
python3 klause-bench/exact-projection/compare.py /tmp/work-cases.json
curl -fsS http://192.168.50.104:8420/experiments/866/cases > /tmp/deadline-cases.json
python3 klause-bench/exact-projection/summarize.py /tmp/deadline-cases.json /tmp/deadline
curl -fsS http://192.168.50.104:8420/experiments/867/cases > /tmp/control-cases.json
python3 klause-bench/exact-projection/summarize.py /tmp/control-cases.json /tmp/control
python3 klause-bench/exact-projection/collect_witnesses.py 871 ~/.cache/klause-bench/corpus
```

Verification runs on GitHub CI through `rational-evidence.yml`; it was not run locally.
The full JVM/native and lint/docs gates also run on CI. A four-entry destructuring
style finding in the new rational test table was corrected without changing the
measured implementation. Canonical-value tests cover signs, coprime and reducible
inputs, subnormal boundaries and nonfinite values; existing exact certificate,
cancellation, authority and theory tests remain part of the full gates.

This delivers one validated arithmetic improvement toward #2348. It does not close
the issue: nec-smt remains undecided at these budgets, and the remaining published
bounds, theory assertion/installation and other costs still need investigation.
