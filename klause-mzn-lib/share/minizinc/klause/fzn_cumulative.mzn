% Override std/fzn_cumulative.mzn. MiniZinc's std definition decomposes to a per-
% timepoint forall(t)(b >= sum...) when durations / resources / capacity contain var
% ints; declaring it native here preserves the cumulative call in FlatZinc and routes
% it to klause's extended factor (Cumulative.kt), which reads the current values from
% the var ids at solve time.
predicate fzn_cumulative(array[int] of var int: s,
                         array[int] of var int: d,
                         array[int] of var int: r,
                         var int: b);
