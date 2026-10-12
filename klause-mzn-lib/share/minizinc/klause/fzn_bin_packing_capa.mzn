% Per-bin weight totals use bool_lin_le over assignment booleans.
% The resulting PseudoBoolean rows expose knapsack-cover cuts.
predicate fzn_bin_packing_capa(
  array [int] of int: c,
  array [int] of var int: bin,
  array [int] of int: w,
) =
  forall (i in index_set(bin)) (min(index_set(c)) <= bin[i] /\ bin[i] <= max(index_set(c))) /\
  forall (b in index_set(c)) (
    bool_lin_le([w[i] | i in index_set(bin)], [bin[i] = b | i in index_set(bin)], c[b])
  );
