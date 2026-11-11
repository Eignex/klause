# Quadratic proposal source controls

Self-authored SMT-LIB optimization fixtures, licensed under Apache-2.0.
Every model has a convex quadratic objective, affine constraints and exact optimum zero.
The scalar and coupled interior models have rational minimizers; the boundary model
has minimizer `(2, 3)`, and the integer model has minimizer `(1, 2)`.
The larger diagonal and coupled models have minimizer `x(i) = 1` for every coordinate.
Their affine upper bounds are strictly slack there.

The catalog suite is `smtlib-quadratic-proposals-v1`. Source optimization enters
quadratic active-row proposal generation before exact stationary reconstruction and
certificate checking. These controls cover source execution and do not represent
the external SMT-LIB satisfaction corpus.
