% Preserve the windowed sum call in FlatZinc. Klause lowers each window to exact
% Linear bounds without introducing MiniZinc's prefix-sum array.
predicate fzn_sliding_sum(int: low, int: up, int: seq, array[int] of var int: vs);
