% Auto: keep `arg_sort_int` native (klause has a factor for it) instead of the std decomposition.
predicate klause_arg_sort_int(array [int] of var int: x, array [int] of var int: p);

predicate fzn_arg_sort_int(array [int] of var int: x, array [int] of var int: p) =
    klause_arg_sort_int(x, p);
