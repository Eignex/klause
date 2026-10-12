% Uniform-capacity bin packing uses per-bin bool_lin_le weight bounds
% over assignment booleans, exposing PseudoBoolean knapsack-cover cuts.
predicate fzn_bin_packing(int: c, array [int] of var int: bin, array [int] of int: w) =
  if length(bin) > 0 then
    forall (b in lb_array(bin)..ub_array(bin)) (
      bool_lin_le([w[i] | i in index_set(bin)], [bin[i] = b | i in index_set(bin)], c)
    )
  endif;
