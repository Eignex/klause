% Bin-packing smoke: pack weighted items into capacity-bounded bins, exposing per-bin loads.
% Exercises klause's bin_packing_load decomposition (#434) — the per-bin weight totals lower to
% bool_lin_le (PseudoBoolean) so the knapsack-cover separator can engage. Total weight 16 fits in
% 3 bins of capacity 6 (e.g. {4,2},{3,3},{2,2}).
include "globals.mzn";

int: nItem = 6;
int: nBin = 3;
array[1..nItem] of int: w = [4, 3, 3, 2, 2, 2];
int: cap = 6;

array[1..nItem] of var 1..nBin: bin;
array[1..nBin] of var 0..cap: load;

constraint bin_packing_load(load, bin, w);

solve satisfy;
