% Golomb ruler feasibility: place `m` marks 0 = mark[1] < ... < mark[m] <= maxLen so that
% all C(m,2) pairwise differences are distinct. The all_different is over the differences,
% whose domain 1..maxLen is much larger than the C(m,2) difference variables — a *slack*
% all_different (strict Hall sub-sets / multiple Régin SCCs), unlike the tight n-vars/n-values
% all_differents in latin_square/magic_square/sudoku. With maxLen one below the optimal ruler
% length the instance is UNSAT, giving a deterministic, heuristic-independent conflict count
% that exercises the full LCG conflict/learning machinery on a slack all_different.
%
% Optimal lengths: m=5->11, m=6->17, m=7->25, m=8->34, m=9->44, m=10->55.

include "globals.mzn";

int: m;
int: maxLen;

array[1..m] of var 0..maxLen: mark;
constraint mark[1] = 0;
constraint forall(i in 1..m-1) (mark[i] < mark[i+1]);
constraint mark[m] <= maxLen;

array[int] of var 1..maxLen: diffs =
  [ mark[j] - mark[i] | i in 1..m, j in i+1..m ];
constraint all_different(diffs);

% Symmetry break: first gap shorter than last.
constraint mark[2] - mark[1] < mark[m] - mark[m-1];

solve satisfy;

output [ "marks = ", show(mark), "\n" ];
