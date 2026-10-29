% 0/1 knapsack with 6 items. Exercises:
%   - bool var array
%   - bool_lin (pseudo-Boolean linear)
%   - solve maximize over an int objective
%
% Expected optimum: 220 (items {2, 5, 6}: weights 6+8+5 = 19, values 60+90+70 = 220).

int: n = 6;
array[1..n] of int: weights = [3, 6, 2, 7, 8, 5];
array[1..n] of int: values  = [40, 60, 30, 50, 90, 70];
int: capacity = 20;

array[1..n] of var bool: take;

constraint sum(i in 1..n) (bool2int(take[i]) * weights[i]) <= capacity;

var 0..1000: total = sum(i in 1..n) (bool2int(take[i]) * values[i]);

solve maximize total;

output [ "take = ", show(take), "\ntotal = ", show(total), "\n" ];
