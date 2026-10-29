% Graph coloring on a small fixed graph. Find the chromatic number, i.e. the
% minimum number of colors such that no two adjacent vertices share a color.
% Exercises minimize over an int objective and pairwise reified inequalities.
%
% Graph (Petersen-style, 6 nodes, 9 edges). Expected optimum: 3.

int: nv = 6;
int: ne = 9;
array[1..ne, 1..2] of int: edges =
  [|1, 2 |1, 3 |2, 3 |2, 4 |3, 5 |4, 5 |4, 6 |5, 6 |1, 6 |];

array[1..nv] of var 1..nv: color;
var 1..nv: chromatic;

constraint forall(i in 1..nv) (color[i] <= chromatic);
constraint forall(e in 1..ne) (color[edges[e, 1]] != color[edges[e, 2]]);

solve minimize chromatic;

output [ "color = ", show(color), "\nchromatic = ", show(chromatic), "\n" ];
