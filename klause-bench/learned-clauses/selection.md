# Exact learned-clause reduction selection

The candidate retains precisely the production LBD-plus-propagation-use policy: glue, units and
recently propagating clauses survive, then the remaining capacity goes to lowest LBD with oldest
insertion first for ties. Cap and restart defaults remain unchanged. An unboxed Boolean drop mask
replaces a boxed hash set. A bounded LBD histogram replaces sorting only when the score range is
at most 4,096 and at most four times the eligible count; sparse ranges retain the packed sort.

## Acceptance protocol

The immutable current-main baseline is `88ddc1847`; candidate `df96e6f4e` differs only in private
reduction selection and behavioral tests. `selection-manifest.json` freezes the 32-input discovery
union before candidate outcomes. `selection-lab.json` pins both revisions at cap 4,000, restart
1,000, 50,000 committed decisions, presolve off and one processor. Each input has three alternating
fresh-JVM repetitions on klause-lab AWS workers with a 30-second safety timeout.

Every verdict and logical shared counter must match, including learned watch visits, dropped and
retained counts. All cases must finish or spend the decision allowance, with complete binary
provenance and no reference contradictions. Lower selection cost counts as a mechanism improvement,
not an increased completion result or an end-to-end speedup. Require a reduction-time ratio whose
95% bootstrap upper bound is below one. Report solve phase and subprocess elapsed time separately,
including unknowns; do not promote with a reproducible runtime regression. A timing interval that
includes one establishes no speedup. Broader cap-off controls and the unseen 28-input holdout are
required before merging. CI supplies the full production gate.

The analyzer averages repeat ratios per input, takes their geometric mean, and bootstraps inputs
5,000 times with fixed seed 1631 for percentile 95% intervals. Missing elapsed fields remain null;
legacy records cannot establish subprocess wall time. Reduction ratios exclude pairs with zero
reduction time and disclose their input count.

The local nine-input guard screen preserves all verdicts and shared logical counters. The two new
behavior tests check oldest-first ties and lower-LBD preference in dense and sparse score ranges.
Local timing is development evidence only.

## Discovery result

Lab [835](http://192.168.50.104:8420/experiments/835) completes all 192 cases with no runner
errors or decided reference disagreements. Both arms solve ten inputs in every repetition,
with 30 SAT and 66 unknown records each. Every one of the 96 pairs finishes or spends the
decision allowance and has identical verdicts and logical shared counters.

The per-input reduction-time ratio over 25 inputs with reductions is 0.864, with 95% interval
[0.829, 0.898]. Total reduction time falls from 2.035 to 1.776 seconds. Solve-phase ratio is
1.017 [0.991, 1.043], common completed-input solve ratio 1.016 [0.963, 1.059], and subprocess
elapsed ratio 1.014 [0.989, 1.039]. Total subprocess elapsed time is 564.650 versus 568.794 seconds.
This establishes lower reduction cost; it establishes neither overall speedup nor overall regression.
The predeclared holdout and broader cap-off controls are running before a merge decision.
