var 1..3: x;
var -3..-1: y;
constraint pow(x, 4) = 16;
constraint pow(y, 3) = -8;
solve satisfy;
output ["x = ", show(x), ";\ny = ", show(y), ";\n"];
