var -8..8: dividend;
var -4..4: divisor;
constraint divisor != 0;
constraint dividend div divisor = 2;
constraint dividend mod divisor = -1;
solve minimize dividend;
output ["dividend = ", show(dividend), ";\ndivisor = ", show(divisor), ";\n"];
