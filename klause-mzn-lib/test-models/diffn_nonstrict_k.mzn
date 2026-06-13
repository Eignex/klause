% diffn_nonstrict_k smoke: k-dimensional non-overlapping boxes (k = 2 here). Exercises klause's
% handling of the std diffn_k decomposition (reified disjunctions) — verifies the niche global runs
% (#435). Two unit-ish boxes always fit apart in a 4×4 area, so it is satisfiable.
include "globals.mzn";

array[1..2, 1..2] of var 0..3: pos;
array[1..2, 1..2] of var 1..2: siz;

constraint diffn_nonstrict_k(pos, siz);

solve satisfy;
