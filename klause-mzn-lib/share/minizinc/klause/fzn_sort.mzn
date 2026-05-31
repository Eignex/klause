% Auto: keep `sort` native (klause has a factor for it) instead of the std decomposition.
predicate klause_sort(array [int] of var int: x, array [int] of var int: y);

predicate fzn_sort(array [int] of var int: x, array [int] of var int: y) =
    klause_sort(x, y);
