% N×N latin square: each row and each column is a permutation of 1..N.
% Exercises alldifferent over array views. For N=4 there are 576 solutions.

include "globals.mzn";

int: n = 4;
array[1..n, 1..n] of var 1..n: x;

constraint forall(i in 1..n) (all_different([x[i, j] | j in 1..n]));
constraint forall(j in 1..n) (all_different([x[i, j] | i in 1..n]));

solve satisfy;

output [
  if j = n then show(x[i, j]) ++ "\n" else show(x[i, j]) ++ " " endif
  | i in 1..n, j in 1..n
];
