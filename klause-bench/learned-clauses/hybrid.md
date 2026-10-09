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
