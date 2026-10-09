# Protected learned-clause ranking experiment

The production PRs remain unmerged pending positive solver measurements. The initial activity
policies and restart changes lost completions. This follow-up holds the existing glue and recent
propagation-use protections, changes the ranking of eligible deletions, and records rejected
configurations instead of promoting them.

A nine-input local fixed-decision screen includes density-175 seeds 4 and 20 and density-210 seed 18,
the completion losses from earlier lab runs. Both caps of pure activity ranking with recent-use
protection lose a completion. Activity as a tie-break within the existing LBD order loses seed 20
at cap 2,000; cap 4,000 preserves the tested completions. Those local timings are not acceptance
measurements: the host was shared and part of the screen overlapped compilation.

The nominated candidate is therefore `lbd-activity`, cap 4,000, restart 1,000. Its conflict-activity
callback is inline so non-tiered resolution does not allocate an unused LBD-computation closure.
The scoring and branching sequence are unchanged by that inlining.

`hybrid-manifest.json` freezes the 32-input union of the original discovery and actual exploratory
inputs: 25 SAT and seven UNSAT reference oracles. `hybrid-lab.json` compares the candidate and legacy
rule at 50,000 committed decisions with a 30-second safety timeout, three alternating fresh-JVM
repetitions, presolve off, one solver processor and four AWS workers. Do not promote a candidate
with any lost completion or reference contradiction. Require a repeatable runtime benefit under
fixed decisions and check common solved-input timing separately from unknowns. Missing records,
wrong corpus selections and binary-provenance gaps invalidate a run.

The 28-input holdout remains unseen and excludes the entire discovery union. Launch it only if
this candidate meets discovery acceptance. Broader format controls and CI are required before
merging production code. The cap and policy defaults remain unchanged until evidence supports a
specific default change.

## Recorded result

Lab [827](http://192.168.50.104:8420/experiments/827) completed all 192 cases at immutable
revision `f60801bc1`. Both rules solve ten of the 32 inputs in all three repetitions, with
30 SAT and 66 unknown records per arm. All cases finish or spend 50,000 committed decisions.
There are no lost or gained completions, runner errors, or decided reference disagreements.

The per-input mean solve-time ratio is 1.017 across all inputs and 1.003 over the ten common
completed inputs. Learned watch visits rise from 548.8 million to 555.6 million. Total reduction
time rises from 2.358 seconds to 4.012 seconds. The hybrid has not demonstrated a positive solver
change and does not qualify for a holdout or promotion. Its code remains on the
[research branch](https://github.com/Eignex/klause/tree/codex/learned-clause-retention-hybrid).

A subsequent local four-input screen of variable activity branching loses all three known SAT
completions under both retention rules at the same decision allowance. Those timings are
development observations; this direction is rejected without a cloud acceptance claim.

The CSV preserves all repetitions, logical counters and binary fingerprints. The JSON records
corpus and revision checks plus the raw payload hash; lab stats and reference reports are retained.
