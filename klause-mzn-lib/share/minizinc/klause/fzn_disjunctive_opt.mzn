function bool: klause_positive_fixed_durations(array[int] of var int: d) =
  if length(d) > 1 /\ forall(di in d)(is_fixed(di)) then
    forall(di in d)(fix(di) > 0)
  else
    false
  endif;

predicate fzn_disjunctive_opt(array[int] of var opt int: s, array[int] of var int: d) =
  if klause_positive_fixed_durations(d) then
    klause_disjunctive_opt(
      [occurs(si) | si in s],
      [if is_fixed(occurs(si)) then
         if fix(occurs(si)) then deopt(si) else 0 endif
       else deopt(si) endif | si in s],
      [fix(di) | di in d])
  else
    forall(i, j in index_set(d) where i < j)(
      absent(s[i]) \/ absent(s[j]) \/ d[i] = 0 \/ d[j] = 0 \/
      deopt(s[i]) + d[i] <= deopt(s[j]) \/ deopt(s[j]) + d[j] <= deopt(s[i]))
  endif;
