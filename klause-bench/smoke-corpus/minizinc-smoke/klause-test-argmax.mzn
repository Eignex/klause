% arg_max selects the maximum element's index using int_max and reified comparisons.
include "globals.mzn";

array[1..4] of var 0..3: x;
var 1..4: m;

constraint m = arg_max(x);
constraint x[3] = 3;

solve satisfy;
