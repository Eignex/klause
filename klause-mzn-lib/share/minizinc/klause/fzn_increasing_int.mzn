% Maps `increasing_int` to its native factor.
predicate klause_increasing_int(array [int] of var int: x);

predicate fzn_increasing_int(array [int] of var int: x) =
    klause_increasing_int(x);
