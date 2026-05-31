% Auto: keep `among` native (klause has a factor for it) instead of the std decomposition.
predicate klause_among(var int: n, array [int] of var int: x, set of int: v);

predicate fzn_among(var int: n, array [int] of var int: x, set of int: v) =
    klause_among(n, x, v);
