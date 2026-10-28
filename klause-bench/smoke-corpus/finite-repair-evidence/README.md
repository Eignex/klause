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
Every declaration is a named finite integer input.
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

`linear-transport` is a self-authored 3×3 transportation source under the same
Apache-2.0 license, with integer inputs `0..2`, every row and column summing to
two, and costs `4 1 3 / 2 4 1 / 1 3 4`. The center `1,1,0,0,1,1,1,0,1` (row-major)
has objective 15. The optimum 6 is unique at `x01=x12=x20=2`, since each row's
cheapest cell is distinct. Every change that keeps all sums is a bipartite cycle,
so any improving completion changes at least four inputs and no three suffice.

`linear-kernel-weighted` retains `2x+3y+5z=16`, `3x+5y+7z=24`,
`x in 0..8` and `y,z in 0..2`, minimizing `x` from `(8,0,0)`. Its feasible
points are `(8,0,0)`, `(4,1,1)` and `(0,2,2)`. The direction `(-4,1,1)`
preserves both equalities; full CP repair and sparsification remain controls.

`linear-kernel-ring33` retains `2x_i+3x_(i+1)=5x_(i+2)` modulo 33, with
domains `0..2`, minimizing `-sum(x_i)` from zero. At a maximum coordinate,
the equality forces both predecessors to have that value; all feasible vectors
are constant. The direction of 33 ones reaches objective `-33`, and twice it
reaches the optimum `-66`. Larger finite repairs and restarts remain controls.
These two sources are self-authored under Apache-2.0. Exact mathematical
expectations are distinct from executed solver or checker outcomes.

| Source | SHA-256 |
|---|---|
| `linear-kernel-weighted.xml` | `ba1d70959116f8dd590374935cb48c09a8f89d2a9a51c510cac5833621816086` |
| `linear-kernel-ring33.xml` | `1d2e9b545cd9bfe3a5338c77d226d66b905abc6c82ebfa6ede147e1cdad0584e` |
