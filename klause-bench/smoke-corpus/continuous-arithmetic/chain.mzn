var 0.0..1.0: x;
var 0.0..1.0: y;
var 0.0..1.0: intermediate;
var 0.0..1.0: z;
constraint x * y = intermediate;
constraint intermediate * intermediate = z;
solve minimize z;
output ["x = ", show(x), ";\ny = ", show(y), ";\nintermediate = ", show(intermediate), ";\nz = ", show(z), ";\n"];
