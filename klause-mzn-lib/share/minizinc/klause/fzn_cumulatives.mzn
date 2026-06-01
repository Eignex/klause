% Override std/fzn_cumulatives.mzn. MiniZinc's std definition decomposes the multi-machine
% cumulative to a per-task forall over per-time loads; declaring it native here preserves the
% cumulatives call in FlatZinc and routes it to klause's graded factor (Cumulatives.kt), which
% reads start / duration / resource / machine / bound values from the var ids at solve time.
predicate fzn_cumulatives(array[int] of var int: s,
                          array[int] of var int: d,
                          array[int] of var int: r,
                          array[int] of var int: m,
                          array[int] of var int: b,
                          bool: upper,
                          int: min_m);
