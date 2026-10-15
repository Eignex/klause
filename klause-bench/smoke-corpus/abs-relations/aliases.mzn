var -5..5: x;
var -5..5: a;
var 0..10: distance;
constraint x = abs(x);
constraint distance = abs(x - a);
constraint distance = 3;
solve satisfy;
output ["x = ", show(x), ";\na = ", show(a), ";\ndistance = ", show(distance), ";\n"];
