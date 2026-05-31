% Auto: keep `all_equal_int` native (klause has a factor for it) instead of the std decomposition.
predicate klause_all_equal_int(array [int] of var int: x);

predicate fzn_all_equal_int(array [int] of var int: x) =
    klause_all_equal_int(x);
