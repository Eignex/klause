% Auto: keep `value_precede_int` native (klause has a factor for it) instead of the std decomposition.
predicate klause_value_precede_int(int: s, int: t, array [int] of var int: x);

predicate fzn_value_precede_int(int: s, int: t, array [int] of var int: x) =
    klause_value_precede_int(s, t, x);
