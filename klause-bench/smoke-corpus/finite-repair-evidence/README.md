# Finite evidence removal control

`latin4.xml` is a self-authored XCSP3 minimization source, licensed under the
repository's Apache-2.0 license. All sixteen coordinates are named source inputs;
rows and columns are permutations of 0..3. A single freed coordinate cannot change
its value with the other coordinates pinned. A four-cell Latin trade can change
the assignment while preserving both sets of constraints.

The feasible center `3,2,1,0,2,3,0,1,1,0,3,2,0,1,2,3` has objective 57. The optimum
is 24: every row contributes six to the unit-weight part, and the additional
diagonal weights are nonnegative. The feasible center
`0,1,2,3,1,0,3,2,2,3,0,1,3,2,1,0` attains that bound. These exact expectations
exercise finite CP acquisition and matched freedom controls; they establish no
ordinary-route performance result or independent checker verdict.

The `linear-*.xml` controls are self-authored under the same Apache-2.0 license.
Every declaration is a finite binary input with unchanged source coordinates.
`linear-exchange` has center `1,0,0,0,0,0`, objective one and optimum zero;
both `x0` and `x3` must change to improve. `linear-three-cycle` has center
`1,0,0,1,0,0,1`; `linear-four-cycle` has center `1,0,0,1,0,0,1,0,0,1`.
Each has objective one and optimum zero at all-zero inputs, and every improving
completion changes all three or four active inputs. Other declarations are
unrelated inputs. A scoped refutation need not mention every active input, so
freeing its support alone need not admit an improving completion.

`linear-knapsack` has center `1,0,0,1,0,0,1,0`, objective three and integer optimum
two, attained by `1,0,0,1,0,0,0,0`. One selected input cannot supply weight five;
`x0=x3=1` does. Its declared-row relaxation has optimum `4/3`, attained by
`x0=0`, `x3=1/3`, `x6=1`. This point is relaxation guidance, not an integer
source witness. Numerical cuts can change the working relaxation; the retained
cut mask and exact point determine each observation.
