% Auto: keep `lex_lesseq_int` native (klause has a factor for it) instead of the std decomposition.
predicate klause_lex_lesseq_int(array [int] of var int: x, array [int] of var int: y);

predicate fzn_lex_lesseq_int(array [int] of var int: x, array [int] of var int: y) =
    klause_lex_lesseq_int(x, y);
