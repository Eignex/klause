% Maps `member_int` to its native factor.
predicate klause_member_int(array [int] of var int: x, var int: y);

predicate fzn_member_int(array [int] of var int: x, var int: y) =
    klause_member_int(x, y);
