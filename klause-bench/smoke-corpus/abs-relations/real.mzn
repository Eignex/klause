var -2.0..2.0: x;
var 0.0..2.0: y;
constraint y = abs(x);
constraint 3.0 * x = -1.0;
solve satisfy;
output ["x = ", show(x), ";\ny = ", show(y), ";\n"];
