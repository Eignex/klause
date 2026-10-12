% Both all_different_except_0 spellings forward to klause_alldifferent_except_0.
% The native AllDifferent factor excludes zero from Regin matching and Hall reasoning.
predicate klause_alldifferent_except_0(array[int] of var int: x);

predicate fzn_alldifferent_except_0(array[int] of var int: x) =
    klause_alldifferent_except_0(x);
