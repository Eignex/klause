% Override std/fzn_sliding_sum.mzn. MiniZinc's std definition decomposes the windowed sum to
% a prefix-sum array plus per-window linear bounds; declaring it native here preserves the
% sliding_sum call in FlatZinc and routes it to klause's graded factor (SlidingSum.kt).
predicate fzn_sliding_sum(int: low, int: up, int: seq, array[int] of var int: vs);
