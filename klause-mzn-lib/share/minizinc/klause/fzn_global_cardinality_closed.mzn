% Override std/fzn_global_cardinality_closed.mzn. Same rationale as fzn_global_cardinality
% — declaring native keeps the GCC intact for klause's GAC propagator; the closed flag is
% threaded through the FlatZinc compiler dispatch (FlatZincConstraints.kt).
predicate fzn_global_cardinality_closed(array[int] of var int: x,
                                        array[int] of int: cover,
                                        array[int] of var int: counts);
