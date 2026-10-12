% Load equalities imply per-bin bool_lin_le bounds using ub(load(b)).
% These redundant PseudoBoolean rows expose knapsack-cover cuts; unbounded loads
% yield trivial rows.
predicate fzn_bin_packing_load(
  array [int] of var int: load,
  array [int] of var int: bin,
  array [int] of int: w,
) =
  sum(load) = sum(w) /\
  forall (i in index_set(bin)) (min(index_set(load)) <= bin[i] /\ bin[i] <= max(index_set(load))) /\
  forall (b in index_set(load)) (
    load[b] = sum (i in index_set(bin)) (w[i] * (bin[i] = b)) /\
    bool_lin_le([w[i] | i in index_set(bin)], [bin[i] = b | i in index_set(bin)], min(ub(load[b]), sum(w)))
  );
