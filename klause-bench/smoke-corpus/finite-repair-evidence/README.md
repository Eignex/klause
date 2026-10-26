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
