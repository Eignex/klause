# Shared learned-clause retention

The shared store can retain clauses by LBD plus propagation use, decaying conflict activity,
glue-protected activity, or dynamic-LBD tiers. These policies apply only to clauses the shared
session owns. Native CP clauses keep their existing owner and retention rule.

Activity bumps the learned initial conflict, every learned reason traversed during resolution,
and new/relearned clauses. Its bump quantum decays lazily by `1 / 0.999` per analyzed conflict,
with `1e20` / `1e-20` rescaling. Binary and locked clauses are protected. Explanations carry an
internal stable reference to their clause's metadata, surviving watch movement and compaction;
activity never locates a reason by comparing its literals against the database.

This is a MiniSat-style ranking inside Klause's fixed-cap, root-restart lifecycle, not MiniSat's
adaptive database sizing or half-database schedule. The glue hybrid adds the configured glue
threshold. The tiered policy monotonically improves LBD during analysis, keeps glue as its core,
protects LBD <= 6 for two reduction intervals after analysis, and ranks the remaining local clauses
by activity. It is a Klause tier policy, not a claim to reproduce a complete Glucose solver.

Sources for the activity and dynamic-LBD mechanisms:

- https://github.com/niklasso/minisat/blob/master/minisat/core/Solver.cc
- https://github.com/niklasso/minisat/blob/master/minisat/core/Solver.h
- https://github.com/audemard/glucose/blob/master/core/Solver.cc

## Discovery protocol

`activity-manifest.json` freezes 24 DTP inputs with SHA-256 and the klause-lab Z3 oracle verdicts:
17 SAT and 7 UNSAT. It includes the prior pilot's densities/seeds, a denser UNSAT-rich group,
and six additional SAT inputs. A local five-input development pilot informs the cap range;
it does not count as acceptance evidence.

`activity-lab.json` compares all four rules at 50,000 committed decisions, cap 2,000, restart
1,000, presolve off and a 30-second safety timeout. Each arm has three alternating fresh-JVM
repetitions. Both policies and workload are frozen before the lab run. Report per-input verdicts,
decisions, theory work, watch visits, peak retention, reduction time and total runtime. Exclude
wall-time-stopped pairs from equal-decision comparisons; retain them in end-to-end comparisons.

Do not promote a rule based on watch visits alone. Require reference parity, no lost completions,
and a repeatable runtime benefit on the discovery set, then confirm the selected policy on a
separate frozen holdout and broad format controls. Compare the candidate's default LBD path against
the unchanged baseline too. A schedule comparison must hold the policy/cap fixed while varying
restart cadence. Defaults are selected only after those results; the cap is otherwise off.
