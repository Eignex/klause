% Override std/fzn_exactly_int.mzn (body `count(x, v) = n`, which decomposes via count). klause
% maps exactly_int to a native Cardinality (#true{x[i]=v} = n) (#517).
predicate fzn_exactly_int(int: n, array [int] of var int: x, int: v);
