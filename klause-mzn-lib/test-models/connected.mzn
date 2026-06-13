% connected smoke: the selected node/edge subgraph must be connected. Triangle graph with all
% nodes selected — picking any two of its three edges connects them. Exercises klause's handling of
% the std connected → reachable decomposition (reified reachability over the edges); verifies the
% niche global runs (#435).
include "globals.mzn";

array[1..3] of int: from = [1, 2, 3];
array[1..3] of int: to = [2, 3, 1];
array[1..3] of var bool: ns;
array[1..3] of var bool: es;

constraint connected(from, to, ns, es);
constraint forall(i in 1..3) (ns[i]);

solve satisfy;
