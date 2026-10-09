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

## Recorded discovery runs

Lab [811](http://192.168.50.104:8420/experiments/811) is an exploratory run, not the intended frozen
discovery: substring name selection included all 20 density-175 inputs and only four density-210
inputs. `activity-exploratory-manifest.json` records what it actually ran. All 288 cases completed;
all four policies agreed with the reference on every completed verdict. At cap 2,000, LBD-plus-use
solved nine inputs and each new policy solved eight. Density-175 seed 20 lost SAT in all three repeats.
The lab's completion-penalized time ratio is 1.36 for activity, 1.38 for glue activity and 1.34 for
tiers. Lower watch visits and shorter time spent on unknowns do not justify adopting those settings.

Name filters use a leading `*` to activate anchored glob matching and prevent a seed such as `s2`
from also selecting `s20`. `analyze.py` rejects a corpus that differs from its manifest, missing or
unfinished records, missing build provenance, wrong revisions and reference disagreements. The
condensed CSV includes every repetition's verdict, logical work, runtime, metadata and binary
fingerprint; the JSON pins the raw cases payload with SHA-256.

The corrected discovery uses the original 24-input manifest at cap 4,000, the second cap selected
in the local development pilot. `activity-cap4000-lab.json` holds policy, restart cadence and budget
constant. Activity is the nominated candidate because it is the simplest alternative and the local
pilot favored it. The 28-input holdout excludes both the planned discovery and every exploratory
input, freezes seven SAT and 21 UNSAT oracles, and is launched only if the corrected discovery meets
the completion/runtime gate. No outcome of the holdout informed its selection.

Reproduce a report after downloading a completed run's `/cases` and `/arms` JSON:

```sh
python3 klause-bench/learned-clauses/analyze.py cases.json \
  klause-bench/learned-clauses/activity-manifest.json arms.json \
  --baseline lbd-use --output activity-results
```
