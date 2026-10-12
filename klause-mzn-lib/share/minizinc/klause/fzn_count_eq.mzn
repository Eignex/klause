% Fixed counted values use klause_count_eq, a single-value GlobalCardinality cover.
% Variable counted values use the reified-equality sum decomposition.
predicate klause_count_eq(array [int] of var int: x, int: y, var int: c);

predicate fzn_count_eq(array [int] of var int: x, var int: y, var int: c) =
  if is_fixed(y) then
    klause_count_eq(x, fix(y), c)
  else
    c = sum (i in index_set(x)) (x[i] = y)
  endif;
