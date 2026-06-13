% Override std/fzn_bin_packing.mzn (uniform capacity c for every bin). Emits the per-bin weight
% total as `bool_lin_le` — `Σ_i w[i]·(bin[i] = b) ≤ c` — which klause maps to a PseudoBoolean so the
% knapsack-cover separator engages (#434). Equivalent to the std complement form
% `Σ w[i]·(bin[i] != b) ≥ Σw − c`, but in the direct `≤ c` shape the cover separator needs.
predicate fzn_bin_packing(int: c, array [int] of var int: bin, array [int] of int: w) =
  if length(bin) > 0 then
    forall (b in lb_array(bin)..ub_array(bin)) (
      bool_lin_le([w[i] | i in index_set(bin)], [bin[i] = b | i in index_set(bin)], c)
    )
  endif;
