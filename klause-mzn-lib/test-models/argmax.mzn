% arg_max smoke: m is the index of the maximum element. Exercises klause's handling of the std
% arg_max decomposition (int_max + reified comparisons) — verifies the niche global runs (#435).
include "globals.mzn";

array[1..4] of var 0..3: x;
var 1..4: m;

constraint m = arg_max(x);
constraint x[3] = 3;

solve satisfy;
