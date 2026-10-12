% Maps `all_equal_int` to its native factor.
predicate klause_all_equal_int(array [int] of var int: x);

predicate fzn_all_equal_int(array [int] of var int: x) =
    klause_all_equal_int(x);
