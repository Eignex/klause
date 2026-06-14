% Override std/redefinitions-2.0.mzn. MiniZinc loads every version-keyed redefinitions file
% (redefinitions-2.0.mzn, -2.1, … up to the running version) from the std library UNLESS the
% solver library shadows it by basename. Those files carry decomposition *bodies* for builtins;
% a solver that handles a builtin natively must shadow the file and drop the body, otherwise the
% std decomposition wins (a body beats a bare `predicate p(...);` declaration elsewhere — so the
% bare decls in our redefinitions.mzn alone are NOT enough).
%
% We keep the decompositions for builtins klause has no native consumer for, and let the int
% array min/max pass through to klause's ArrayMinMax factor: `min([...])` / `max([...])` over a
% var-int list lowers to array_int_minimum/array_int_maximum, and the std body here expands each
% into a chain of n-1 pairwise int_min/int_max plus n intermediate vars — quadratic model blow-up
% (e.g. road-cons: ~3300 int_min instead of ~520 native minimums) that throttles propagation.
% array_int_minimum/array_int_maximum are bare-declared in redefinitions.mzn, so omitting their
% bodies here makes them opaque builtins that the front-end maps to a single ArrayMinMax. (#492)

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
