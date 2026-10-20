% Maps `symmetric_all_different` to its native factor.
predicate klause_symmetric_all_different(array [int] of var int: x);

predicate fzn_symmetric_all_different(array [int] of var int: x) =
    klause_symmetric_all_different([x[i] - min(index_set(x)) + 1 | i in index_set(x)]);
