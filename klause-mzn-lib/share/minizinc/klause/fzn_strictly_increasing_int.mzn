% Maps `strictly_increasing_int` to its native factor.
predicate klause_strictly_increasing_int(array [int] of var int: x);

predicate fzn_strictly_increasing_int(array [int] of var int: x) =
    klause_strictly_increasing_int(x);
