% Standard 9×9 sudoku, with a fixed easy puzzle as the seed.
% Exercises alldifferent over rows, columns, and 3×3 blocks plus initial pinning.

include "globals.mzn";

int: n = 9;
int: m = 3;   % block size

% 0 = blank; non-zero = given.
array[1..n, 1..n] of int: puzzle =
  [|5, 3, 0, 0, 7, 0, 0, 0, 0
   |6, 0, 0, 1, 9, 5, 0, 0, 0
   |0, 9, 8, 0, 0, 0, 0, 6, 0
   |8, 0, 0, 0, 6, 0, 0, 0, 3
   |4, 0, 0, 8, 0, 3, 0, 0, 1
   |7, 0, 0, 0, 2, 0, 0, 0, 6
   |0, 6, 0, 0, 0, 0, 2, 8, 0
   |0, 0, 0, 4, 1, 9, 0, 0, 5
   |0, 0, 0, 0, 8, 0, 0, 7, 9 |];

array[1..n, 1..n] of var 1..n: x;

constraint forall(i, j in 1..n where puzzle[i, j] > 0) (x[i, j] = puzzle[i, j]);

constraint forall(i in 1..n) (all_different([x[i, j] | j in 1..n]));
constraint forall(j in 1..n) (all_different([x[i, j] | i in 1..n]));
constraint forall(bi, bj in 0..m-1)
  (all_different([x[bi*m + i, bj*m + j] | i, j in 1..m]));

solve satisfy;

output [
  if j = n then show(x[i, j]) ++ "\n" else show(x[i, j]) ++ " " endif
  | i in 1..n, j in 1..n
];
