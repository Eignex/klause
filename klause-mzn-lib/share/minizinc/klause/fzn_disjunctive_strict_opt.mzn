include "fzn_disjunctive_opt.mzn";
include "fzn_disjunctive_strict_opt_decomp.mzn";

predicate fzn_disjunctive_strict_opt(array[int] of var opt int: s, array[int] of var int: d) =
  if klause_positive_fixed_durations(d) then
    fzn_disjunctive_opt(s, d)
  else
    fzn_disjunctive_strict_opt_decomp(s, d)
  endif;
