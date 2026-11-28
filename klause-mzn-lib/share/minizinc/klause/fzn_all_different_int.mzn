% Shadows std/fzn_all_different_int.mzn by basename. The opaque builtin reaches the native AllDifferent
% factor instead of a pairwise decomposition that loses the permutation structure local search needs.
predicate klause_all_different_int(array[int] of var int: x);

predicate fzn_all_different_int(array[int] of var int: x) =
    klause_all_different_int(x);
