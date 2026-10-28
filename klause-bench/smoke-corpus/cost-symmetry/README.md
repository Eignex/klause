# Cost symmetry controls

Self-authored Apache-2.0 sources retain two independent transform questions.

`equality-cost.xml` uses the nine 0..2 transportation inputs and six row/column
sums of two. Its cost equals `90 + x00 + x11 + x22` on feasible points.
Transposition changes the cost by `10*(colSum1-rowSum1) +
20*(colSum2-rowSum2)`, so the doubled forward and reverse three-cycles both cost
90. Increasing the `x01` cost by one makes those cycle costs 92 and 90,
distinguishing the equality certificate from mere constraint invariance.
Preparation may remove dependent equations; source
symmetry alone does not establish a prepared-model automorphism.

`signed-cost.wcnf` has hard clauses `(b0 or b2)` and `(not b1 or not b3)`.
Unit soft clauses give exact cost `2+b0-b1+b2-b3`. Complemented reversal
`T(b)=(1-b3,1-b2,1-b1,1-b0)` preserves every weighted clause and the cost,
including its constant. Positive-cost coordinates
`y=(b0,1-b1,b2,1-b3)` turn both hard clauses into positive covering clauses;
the cost becomes `sum(y)`. Each disjoint covering clause requires one true
literal, and `b=(0,1,1,0)` attains cost two. This orientation is a detection option, not an
unsigned map over the original variable ids.

These are small mechanism controls, not ordinary-source performance evidence.
Lab records, prepared-model captures and resource receipts remain outside Git.
