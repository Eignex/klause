% Override std/fzn_at_most_int.mzn (body `count(x, v) <= n`, which decomposes via count). klause
% maps at_most_int to a native Cardinality (#true{x[i]=v} ≤ n) (#517).
predicate fzn_at_most_int(int: n, array [int] of var int: x, int: v);
