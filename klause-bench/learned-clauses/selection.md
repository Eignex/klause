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
The predeclared holdout confirms the lower reduction cost, and the broader cap-off controls pass
the gates below.

## Holdout result

Lab [838](http://192.168.50.104:8420/experiments/838) completes all 168 cases on the 28 unseen
inputs. All 84 pairs spend exactly 50,000 committed decisions and match every verdict and logical
shared counter. All outcomes are unknown in both arms; no completion improvement or solved-input
timing claim follows from this holdout. There are no runner errors or reference contradictions.

The per-input reduction-time ratio is 0.868, with 95% interval [0.835, 0.900]. Total reduction
time falls from 2.422 to 2.116 seconds. Solve-phase ratio is 0.982 [0.952, 1.013] and subprocess
elapsed ratio 0.981 [0.952, 1.012]. The reduction benefit repeats, while overall timing remains
inconclusive.

Pooling the two disjoint frozen manifests gives 60 inputs and 180 pairs, all with identical verdicts
and logical counters. The per-input reduction ratio is 0.866 [0.843, 0.889] across the 53 inputs
with reductions. Pooled solve-phase ratio is 1.000 [0.980, 1.021] and subprocess elapsed ratio
0.998 [0.979, 1.018]. This is neutral overall timing with a repeatable 13.4% reduction-cost benefit.
`selection-pooled-manifest.json` is the concatenation of the discovery and holdout manifests;
`selection-pooled-results` uses their concatenated cases payload and the same pinned arms.

## Default-path control

Lab [839](http://192.168.50.104:8420/experiments/839) completes all 378 cases across the frozen
63-input, nine-suite, six-format coverage manifest at cap off and a 30-second safety timeout.
Both arms report 54 SAT, 42 UNSAT and 93 unknown records. All 189 paired outcomes agree, and
all 108 pairs where both complete or spend the decision allowance have identical logical counters.
Remaining wall-time-limited pairs are timing observations, not equal-work comparisons. The lab has
reference coverage on 48 inputs with no disagreements or optimization shortfalls.

There are no reductions in any record that emits shared statistics. Subprocess elapsed ratio is
1.004 [0.992, 1.017], solve-phase ratio 1.008 [0.990, 1.025], and common completed-input solve ratio
1.009 [0.976, 1.044]. This supports neutral default-path timing. The candidate emits 123,529
asserting and zero non-asserting shared conflicts over 186 records. Three root Farkas refutations
emit no shared statistics and remain explicitly missing rather than counted as zero.

## Disposition

The repeatable improvement is lower reduction cost with identical retained clauses and search work.
The cap-off and LBD-plus-use defaults remain unchanged. The measured activity and cadence alternatives
do not qualify for promotion; these results do not establish a general result for every future
activity policy or corpus. Overall timing is neutral within the intervals, with no completion gain.

The measured shared-search and pipeline source, SMT frontend and their behavior tests are
byte-identical to `df96e6f4e`. Automatic stack rebasing also integrates main's independent LP-phase
metrics; those changes were not part of the pinned selection comparison. Local SearchSession tests pass;
the full check, native tests and documentation gate use GitHub CI. The analyzer also rejects missing
cases, unfinished runs, wrong revisions and reference contradictions.
