# Finite repair controls

Self-authored XCSP3 sources, licensed under the repository's Apache-2.0 license.
`assignment-exchange.xml` has optimum zero. At center `1,0,0,0,0,0`, freeing
either assignment coordinate alone admits no strict improvement; freeing both
admits `0,1,0,0,0,0`. The other four declarations are unrelated finite inputs.
Every complement pin and source constraint remains enforced in an exact control.
Neighborhood exhaustion under pins is not an optimality proof for the unpinned source.

`resource-exchange.xml` has the same domains, center and optimum, with two unit
tasks sharing capacity one. `placement-exchange.xml` has two unit rectangles on
the same fixed vertical coordinate and center `1,0,0,0,0,0,0,1`; the last two
declarations fix that coordinate and the unit size. Both require releasing the
two horizontal/start coordinates together. Fixed source roles consume no movable
freedom. These controls do not cover optional tasks, resource choices or variable
rectangle sizes.

The three `*-cycle.xml` controls use active declarations `x0`, `x3`, `x6`
with domains `{0,1}`, `{1,2}`, `{0,2}`. Their only feasible active assignments
are `(0,1,2)` and `(1,2,0)`. Center `0,0,0,1,0,0,2` has objective two;
every improving completion changes all three active inputs. Placement appends
the fixed `y=0`, `one=1` declarations to that center. Other declarations are
unrelated inputs. `assignment-four-cycle.xml` extends the same construction to
four active declarations, with center `0,0,0,1,0,0,2,0,0,3` and optimum zero.
No proper subset of its active inputs admits a strict improvement.
