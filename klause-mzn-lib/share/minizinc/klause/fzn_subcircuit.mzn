% Maps `subcircuit` to its native factor.
predicate klause_subcircuit(array [int] of var int: x);

predicate fzn_subcircuit(array [int] of var int: x) =
    klause_subcircuit([x[i] - min(index_set(x)) + 1 | i in index_set(x)]);
