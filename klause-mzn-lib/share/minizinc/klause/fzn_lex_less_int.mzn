% Auto: keep `lex_less_int` native (klause has a factor for it) instead of the std decomposition.
predicate klause_lex_less_int(array [int] of var int: x, array [int] of var int: y);

predicate fzn_lex_less_int(array [int] of var int: x, array [int] of var int: y) =
    klause_lex_less_int(x, y);
