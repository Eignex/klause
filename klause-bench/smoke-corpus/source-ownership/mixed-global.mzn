% Self-authored Eignex Klause regression fixture. Apache-2.0.
include "all_different.mzn";
var 0..2: n;
var 0..2: m;
var 1.0..4.0: x;
var 4.0..4.0: y;
constraint n != 1;
constraint all_different([n, m]);
constraint int2float(n) * x = y;
solve satisfy;
output ["n = \(n);\nm = \(m);\nx = \(x);\ny = \(y);\n"];
