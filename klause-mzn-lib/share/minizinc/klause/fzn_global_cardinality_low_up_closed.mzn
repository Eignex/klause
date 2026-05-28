% Override std/fzn_global_cardinality_low_up_closed.mzn. Std's body decomposes to a
% forall(x[i] in cover) + global_cardinality_low_up + length-sum implied constraint;
% declaring native keeps the closed lo/hi GCC intact for klause's GAC propagator.
predicate fzn_global_cardinality_low_up_closed(array[int] of var int: x,
                                               array[int] of int: cover,
                                               array[int] of int: lbound,
                                               array[int] of int: ubound);
