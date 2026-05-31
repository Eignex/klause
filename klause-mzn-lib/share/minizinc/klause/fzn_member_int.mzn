% Auto: keep `member_int` native (klause has a factor for it) instead of the std decomposition.
predicate klause_member_int(array [int] of var int: x, var int: y);

predicate fzn_member_int(array [int] of var int: x, var int: y) =
    klause_member_int(x, y);
