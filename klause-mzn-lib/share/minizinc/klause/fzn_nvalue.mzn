% Auto: keep `nvalue` native (klause has a factor for it) instead of the std decomposition.
predicate klause_nvalue(var int: n, array [int] of var int: x);

predicate fzn_nvalue(var int: n, array [int] of var int: x) =
    klause_nvalue(n, x);
