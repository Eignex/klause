# Coupled cost control

`k4.xml` is a self-authored Apache-2.0 XCSP3 optimization source. Six ground
Tables define one private equality cost per edge of K4. Unary label weights are
1, 2, 4, 8 and edge weights are 64 through 69 in order 01, 02, 03, 12, 13, 23.
The mathematical optimum is 136 at labels 1100; that expectation is independent
of measured native solver results. Each individual source row has a feasible
completion. The separate Table hulls admit labels 1/2 and zero costs at 7.5.
A relaxed diagram's underestimated path cost cannot replace a source cost equality.
