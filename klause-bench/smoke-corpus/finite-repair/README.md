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
