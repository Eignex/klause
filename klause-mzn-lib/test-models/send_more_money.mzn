% SEND + MORE = MONEY. Classic alldifferent + linear puzzle.
%
% Expected: S=9 E=5 N=6 D=7 M=1 O=0 R=8 Y=2 (9567 + 1085 = 10652).

include "globals.mzn";

var 1..9: S;
var 0..9: E;
var 0..9: N;
var 0..9: D;
var 1..9: M;
var 0..9: O;
var 0..9: R;
var 0..9: Y;

constraint all_different([S, E, N, D, M, O, R, Y]);

constraint
       1000 * S + 100 * E + 10 * N + D
     + 1000 * M + 100 * O + 10 * R + E
  = 10000 * M + 1000 * O + 100 * N + 10 * E + Y;

solve satisfy;

output [
  "S=", show(S), " E=", show(E), " N=", show(N), " D=", show(D),
  " M=", show(M), " O=", show(O), " R=", show(R), " Y=", show(Y), "\n"
];
