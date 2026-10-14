var 1..3: x;
var 0..3: y;
var 0..3: z;
constraint x * y = x;
constraint y * x = x;
constraint z * z = z;
solve satisfy;
output ["x = ", show(x), ";\ny = ", show(y), ";\nz = ", show(z), ";\n"];
