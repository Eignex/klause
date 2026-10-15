var -1.0..1.0: x;
var 0.0..1.0: y;
constraint x * x = y;
solve maximize y;
output ["x = ", show(x), ";\ny = ", show(y), ";\n"];
