% Override std/fzn_disjunctive.mzn (pairwise no-overlap reified decomposition). klause maps
% fzn_disjunctive to the native Disjunctive factor, which reads variable durations from the
% assignment. General var form so variable-duration scheduling passes through rather than
% decomposing into O(tasks^2) reified disjunctions. (#492)
predicate fzn_disjunctive(array[int] of var int: s, array[int] of var int: d);
