% Override std/fzn_all_different_int.mzn. MiniZinc looks up a solver-lib file with the
% same basename as a std file and uses it instead. Forward to klause's namespaced
% `klause_all_different_int` builtin (opaque to MiniZinc, so it's passed through verbatim
% rather than re-decomposed like the recognized `all_different_int`), which klause's
% front-end maps to the native AllDifferent factor — strong propagation + value-swap
% repair moves, instead of the std O(n^2) pairwise int_lin_ne decomposition that loses the
% permutation structure local search needs.
predicate klause_all_different_int(array[int] of var int: x);

predicate fzn_all_different_int(array[int] of var int: x) =
    klause_all_different_int(x);
