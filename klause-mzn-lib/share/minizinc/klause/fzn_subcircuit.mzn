% Auto: keep `subcircuit` native (klause has a factor for it) instead of the std decomposition.
predicate klause_subcircuit(array [int] of var int: x);

predicate fzn_subcircuit(array [int] of var int: x) =
    klause_subcircuit(x);
