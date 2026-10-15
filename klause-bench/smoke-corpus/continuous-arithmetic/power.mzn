var 0.0..2.0: x;
var 0.0..4.0: y;
constraint pow(x, 2.0) = y;
solve maximize y;
output ["x = \(x);\n", "y = \(y);\n"];
