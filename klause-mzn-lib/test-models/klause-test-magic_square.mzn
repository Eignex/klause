% N×N magic square: every row, column, and main diagonal sums to the same value.
% For N=3 there are 8 solutions (one base × 8 symmetries) with magic sum 15.

include "globals.mzn";

int: n = 3;
int: total = n * (n*n + 1) div 2;   % 15 for n=3

array[1..n, 1..n] of var 1..n*n: m;

constraint all_different([m[i, j] | i in 1..n, j in 1..n]);

constraint forall(i in 1..n) (sum(j in 1..n) (m[i, j]) = total);
constraint forall(j in 1..n) (sum(i in 1..n) (m[i, j]) = total);
constraint sum(i in 1..n) (m[i, i]) = total;
constraint sum(i in 1..n) (m[i, n + 1 - i]) = total;

solve satisfy;

output [
  if j = n then show(m[i, j]) ++ "\n" else show(m[i, j]) ++ " " endif
  | i in 1..n, j in 1..n
];
