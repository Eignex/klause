# Strict-cost Table substitution controls

These self-authored Apache-2.0 XCSP3 sources use two five-cycle vertex covers,
one coupling edge and a Table on `x,u(0),u(1)`. Each cycle needs three vertices;
their minimum costs are 18 and 17. The `interior` optimum is 35 at `x=0`.
Every tuple with `x=1` has an otherwise identical `x=0` tuple; replacing it
strictly lowers the objective. The tuple with `x=2` has no such replacement.

`hole` excludes the proposed replacement in its declaration and has optimum 36.
`outside-reader` adds `AllDifferent(x,w)` and has optimum 35. A completion with
`x=1,w=0` rejects the replacement, even though the Table alone permits it.
These are mathematical expectations, not measured solver results.
