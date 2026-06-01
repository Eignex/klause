% Override std/fzn_geost_nonoverlap_k.mzn. MiniZinc's std definition is an `exists`-over-axes
% disjunction that lowers to clauses (no LS gradient). Declaring it native here preserves the
% call in FlatZinc and routes it to klause's graded Geost factor (Geost.kt), which grades the
% pair by overlap volume and proposes separation repair moves. `geost` itself lowers to many
% of these pairwise calls, so each pair gets the native gradient.
predicate fzn_geost_nonoverlap_k(array[int] of var int: x1,
                                 array[int] of int: w1,
                                 array[int] of var int: x2,
                                 array[int] of int: w2);
