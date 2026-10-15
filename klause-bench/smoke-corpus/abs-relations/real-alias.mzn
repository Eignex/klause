var -2.0..2.0: x;
constraint x = abs(x);
solve satisfy;
output ["x = ", show(x), ";\n"];
