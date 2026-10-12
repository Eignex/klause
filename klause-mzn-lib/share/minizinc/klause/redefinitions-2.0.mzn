% Solver files shadow versioned standard redefinitions by basename.
% Integer array min/max remain bodyless, mapping directly to ArrayMinMax;
% other predicates retain their decompositions. A body would override a bare
% declaration from redefinitions.mzn.

predicate bool_clause_reif(array [int] of var bool: as, array [int] of var bool: bs, var bool: b) =
  clause(as, bs ++ [b]) /\
    forall (i in index_set(as)) (as[i] -> b) /\
    forall (i in index_set(bs)) (bs[i] \/ b);

% klause has no native float-array min/max (floats are bucketed to ints), so keep the std
% decomposition for these.
predicate array_float_maximum(var float: m, array [int] of var float: x) =
  let {
    int: l = min(index_set(x));
    int: u = max(index_set(x));
    float: ly = lb_array(x);
    float: uy = ub_array(x);
    array [l..u] of var ly..uy: y;
  } in y[l] = x[l] /\ m = y[u] /\ forall (i in l + 1..u) (y[i] == max(x[i], y[i - 1]));

predicate array_float_minimum(var float: m, array [int] of var float: x) =
  let {
    int: l = min(index_set(x));
    int: u = max(index_set(x));
    float: ly = lb_array(x);
    float: uy = ub_array(x);
    array [l..u] of var ly..uy: y;
  } in y[l] = x[l] /\ m = y[u] /\ forall (i in l + 1..u) (y[i] == min(x[i], y[i - 1]));
