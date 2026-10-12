% Maps `value_precede_chain_int` to its native factor.
predicate klause_value_precede_chain_int(array [int] of int: T, array [int] of var int: X);

predicate fzn_value_precede_chain_int(array [int] of int: T, array [int] of var int: X) =
    klause_value_precede_chain_int(T, X);
