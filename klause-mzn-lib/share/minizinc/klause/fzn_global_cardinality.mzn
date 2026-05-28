% Override std/fzn_global_cardinality.mzn. Std's body decomposes to per-cover
% `count(...)` constraints; declaring native keeps the GCC as one constraint so klause's
% Régin-flow GAC propagator can run.
predicate fzn_global_cardinality(array[int] of var int: x,
                                 array[int] of int: cover,
                                 array[int] of var int: counts);
