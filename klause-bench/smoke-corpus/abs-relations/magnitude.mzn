var -5..5: x;
var 0..5: y;
constraint y = abs(x);
constraint y = 3;
solve satisfy;
output ["x = ", show(x), ";\ny = ", show(y), ";\n"];
