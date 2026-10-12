% Maps `value_precede_int` to its native factor.
predicate klause_value_precede_int(int: s, int: t, array [int] of var int: x);

predicate fzn_value_precede_int(int: s, int: t, array [int] of var int: x) =
    klause_value_precede_int(s, t, x);
