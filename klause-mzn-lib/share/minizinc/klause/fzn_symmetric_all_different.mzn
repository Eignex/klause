% Maps `symmetric_all_different` to its native factor.
predicate klause_symmetric_all_different(array [int] of var int: x);

predicate fzn_symmetric_all_different(array [int] of var int: x) =
    klause_symmetric_all_different(x);
