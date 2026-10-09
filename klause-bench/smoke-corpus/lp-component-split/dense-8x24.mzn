int: blocks = 8;
int: width = 24;
array[1..blocks, 1..width] of var 0.0..2.0: x;
constraint forall(b in 1..blocks, r in 1..width)(
  sum(c in 1..width)((if c = r then width + 1 else 1 endif) * x[b,c]) = 2 * width
);
solve satisfy;
output ["x = " ++ show(x) ++ ";\n"];
