var opt -3..3: x;
var opt 0..3: y;
constraint y = abs(x);
constraint occurs(x) -> deopt(y) = 2;
solve satisfy;
output ["x = ", show(x), ";\ny = ", show(y), ";\n"];
