int: blocks = 8;
int: width = 24;
array[1..blocks, 1..width] of var 0.0..2.0: x;
var 0..1: k;
constraint int2float(k) + x[1,1] >= 1.0;
constraint forall(b in 1..blocks, r in 1..width)(
  sum(c in 1..width)((if c = r then width + 1 else 1 endif) * x[b,c]) = 2 * width
);
constraint sum(b in 1..blocks)(x[b,1]) = blocks;
solve minimize x[1,1];
output ["k = " ++ show(k) ++ ";\nx = " ++ show(x) ++ ";\n"];
