% Maps `subcircuit` to its native factor.
predicate klause_subcircuit(array [int] of var int: x);

predicate fzn_subcircuit(array [int] of var int: x) =
    klause_subcircuit(x);
