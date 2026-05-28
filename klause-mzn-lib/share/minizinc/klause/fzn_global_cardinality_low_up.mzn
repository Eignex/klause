% Override std/fzn_global_cardinality_low_up.mzn. Std's body decomposes to a forall of
% per-cover count(in lbound..ubound); declaring native preserves the constraint so
% klause's GAC propagator handles the lo/hi range form directly.
predicate fzn_global_cardinality_low_up(array[int] of var int: x,
                                        array[int] of int: cover,
                                        array[int] of int: lbound,
                                        array[int] of int: ubound);
