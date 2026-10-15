% Self-authored Eignex Klause regression fixture. Apache-2.0.
var 1..2: n;
var 1.0..4.0: x;
var 4.0..4.0: y;
constraint int2float(n) * x = y;
solve satisfy;
output ["n = \(n);\nx = \(x);\ny = \(y);\n"];
