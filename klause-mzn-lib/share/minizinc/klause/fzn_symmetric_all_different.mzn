% Auto: keep `symmetric_all_different` native (klause has a factor for it) instead of the std decomposition.
predicate klause_symmetric_all_different(array [int] of var int: x);

predicate fzn_symmetric_all_different(array [int] of var int: x) =
    klause_symmetric_all_different(x);
