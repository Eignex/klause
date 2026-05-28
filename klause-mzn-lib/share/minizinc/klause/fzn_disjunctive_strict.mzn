% Override std/fzn_disjunctive_strict.mzn. Routed to the same extended Disjunctive
% factor as the non-strict form (LS-cost is identical; strict semantics on zero-
% duration tasks aren't enforced separately yet).
predicate fzn_disjunctive_strict(array[int] of var int: s, array[int] of var int: d);
