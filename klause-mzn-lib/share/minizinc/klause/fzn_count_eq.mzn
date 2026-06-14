% Override std/fzn_count_eq.mzn (decomposes into a reified-equality sum c = Σ_i (x[i] = y)).
% When the counted value y is fixed (the common case — counting occurrences of a constant),
% route to klause's native GlobalCardinality factor via the opaque klause_count_eq builtin: a
% single-value cover with count var c, propagated by GCC flow reasoning instead of a
% bool2int/int_eq_reif swamp (#517). A genuinely variable y is rare and has no single-value
% cover, so it falls back to the standard reified-sum decomposition.
predicate klause_count_eq(array [int] of var int: x, int: y, var int: c);

predicate fzn_count_eq(array [int] of var int: x, var int: y, var int: c) =
  if is_fixed(y) then
    klause_count_eq(x, fix(y), c)
  else
    c = sum (i in index_set(x)) (x[i] = y)
  endif;
