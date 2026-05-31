% Auto: keep `increasing_int` native (klause has a factor for it) instead of the std decomposition.
predicate klause_increasing_int(array [int] of var int: x);

predicate fzn_increasing_int(array [int] of var int: x) =
    klause_increasing_int(x);
