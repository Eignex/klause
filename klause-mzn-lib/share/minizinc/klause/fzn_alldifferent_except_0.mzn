% Override std/fzn_alldifferent_except_0.mzn. MiniZinc uses a solver-lib file with the same
% basename instead of the std one. Forward to klause's namespaced `klause_alldifferent_except_0`
% builtin (opaque to MiniZinc, so passed through verbatim rather than re-decomposed), which
% klause's front-end maps to the native AllDifferent factor with an excepted-value set {0} — full
% Régin matching / Hall propagation, instead of the std O(n^2) reified pairwise decomposition
% (#433). Both the primary `all_different_except_0` and its `alldifferent_except_0` synonym route
% through this fzn_ predicate, so this catches both spellings.
predicate klause_alldifferent_except_0(array[int] of var int: x);

predicate fzn_alldifferent_except_0(array[int] of var int: x) =
    klause_alldifferent_except_0(x);
