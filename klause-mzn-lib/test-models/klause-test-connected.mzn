% Connected selected subgraph of a triangle: all nodes are selected,
% and any two edges connect them through the reachable decomposition.
include "globals.mzn";

array[1..3] of int: from = [1, 2, 3];
array[1..3] of int: to = [2, 3, 1];
array[1..3] of var bool: ns;
array[1..3] of var bool: es;

constraint connected(from, to, ns, es);
constraint forall(i in 1..3) (ns[i]);

solve satisfy;
