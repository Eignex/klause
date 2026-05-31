% Auto: keep `value_precede_chain_int` native (klause has a factor for it) instead of the std decomposition.
predicate klause_value_precede_chain_int(array [int] of int: T, array [int] of var int: X);

predicate fzn_value_precede_chain_int(array [int] of int: T, array [int] of var int: X) =
    klause_value_precede_chain_int(T, X);
