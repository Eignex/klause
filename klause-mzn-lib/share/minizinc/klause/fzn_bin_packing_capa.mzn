% Override std/fzn_bin_packing_capa.mzn. Same semantics (per-bin capacity), but expresses the
% per-bin weight total as `bool_lin_le` — `Σ_i w[i]·(bin[i] = b) ≤ c[b]` over the assignment
% booleans — which klause maps to a PseudoBoolean factor. That lets the knapsack-cover separator
% strengthen the LP relaxation (#434). The std form sums `w[i]·(bin[i] = b)` as an int_lin_le over
% bool2int channels, which klause sees as a generic Linear that the cover separator can't touch.
predicate fzn_bin_packing_capa(
  array [int] of int: c,
  array [int] of var int: bin,
  array [int] of int: w,
) =
  forall (i in index_set(bin)) (min(index_set(c)) <= bin[i] /\ bin[i] <= max(index_set(c))) /\
  forall (b in index_set(c)) (
    bool_lin_le([w[i] | i in index_set(bin)], [bin[i] = b | i in index_set(bin)], c[b])
  );
