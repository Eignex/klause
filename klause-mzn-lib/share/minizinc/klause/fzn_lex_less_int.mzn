% Maps `lex_less_int` to its native factor.
predicate klause_lex_less_int(array [int] of var int: x, array [int] of var int: y);

predicate fzn_lex_less_int(array [int] of var int: x, array [int] of var int: y) =
    klause_lex_less_int(x, y);
