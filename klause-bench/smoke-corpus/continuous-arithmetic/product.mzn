var 0.0..1.0: x;
var 0.0..1.0: y;
var 0.0..1.0: z;
constraint x * y = z;
solve minimize z;
output ["x = ", show(x), ";\ny = ", show(y), ";\nz = ", show(z), ";\n"];
