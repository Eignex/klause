var 1.0..2.0: x;
var 1.0..4.0: y;
constraint pow(x, 2.0) = y;
solve satisfy;
output ["x = \(x);\n", "y = \(y);\n"];
