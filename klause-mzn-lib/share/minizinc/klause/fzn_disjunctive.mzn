% Override std/fzn_disjunctive.mzn. Same rationale as fzn_cumulative.mzn — declaring
% native preserves disjunctive in FlatZinc, routing var-duration cases to the extended
% Disjunctive.kt factor instead of the std decomposition.
predicate fzn_disjunctive(array[int] of var int: s, array[int] of var int: d);
