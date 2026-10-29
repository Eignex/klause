% Two-dimensional non-overlap with variable box sizes in a 4x4 area.
% The reified-disjunction decomposition admits a satisfying placement.
include "globals.mzn";

array[1..2, 1..2] of var 0..3: pos;
array[1..2, 1..2] of var 1..2: siz;

constraint diffn_nonstrict_k(pos, siz);

solve satisfy;
