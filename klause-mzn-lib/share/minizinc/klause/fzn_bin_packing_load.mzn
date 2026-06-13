% Override std/fzn_bin_packing_load.mzn. Keeps the load-defining equality and adds, per bin, a
% redundant `bool_lin_le` — `Σ_i w[i]·(bin[i] = b) ≤ ub(load[b])` over the assignment booleans —
% which klause maps to a PseudoBoolean, so the knapsack-cover separator can strengthen the LP
% relaxation (#434). The bound is the load var's declared upper bound, so the row is implied by the
% equality and stays sound; when a load is effectively unbounded the row is trivially true (no cut).
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
