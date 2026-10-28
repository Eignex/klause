# Conditional symmetry control

`conditional-cost.wcnf` is a self-authored Apache-2.0 control, with SHA-256
`989c4a4a8b7381f374b2dacf00784af81f2a8dd5cb17850a9094e141661c5b56`.
Source coordinates are `a,b,s,t`. Its three hard clauses are `(s or a)`,
`(t or b)` and `(not s or not t or a or b)`; its five unit soft clauses give
the exact minimized objective `3+a+b-s-2t`.

The connected residual at `s=t=true` admits the `a,b` swap, although the root
factor multiset does not. Both `0111` and `1011` are feasible with cost one.
The third hard clause implies `a+b >= s+t-1`, hence the root LP bound is at
least `2-t >= 1`. This control distinguishes residual symmetry admission from
useful optimization pruning; it supplies no performance claim.
