var -3..3: x;
var bool: magnitude_is_two;
constraint magnitude_is_two <-> abs(x) = 2;
constraint magnitude_is_two \/ x = 0;
solve satisfy;
output ["x = ", show(x), ";\nmagnitude_is_two = ", show(magnitude_is_two), ";\n"];
