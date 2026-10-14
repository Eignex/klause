var 1..3: x;
constraint x * x = 4;
solve satisfy;
output ["x = ", show(x), ";\n"];
