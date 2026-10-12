% Native Cumulative accepts variable starts, durations, resources and capacity.
% Constant arrays use the fast path; variable arrays remain native.
predicate fzn_cumulative(array[int] of var int: s, array[int] of var int: d,
                         array[int] of var int: r, var int: b);
