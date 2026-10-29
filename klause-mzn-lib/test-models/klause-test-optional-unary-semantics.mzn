include "globals.mzn";

int: mode;
mzn_absent_zero = mode != 2;
var opt 1..2: absent_task;
var 0..2: absent_value = deopt(absent_task);
var opt 0..1: overlap_task;
var opt 1..1: point_task;
var 0..1: duration;
var bool: p;
var bool: r;

constraint
  if mode in 1..2 then
    not occurs(absent_task) /\ deopt(absent_task) = 2 /\
    disjunctive([absent_task, 0], [2, 2])
  elseif mode = 3 then
    disjunctive([overlap_task, overlap_task], [2, 2])
  elseif mode = 4 then
    occurs(point_task) /\ disjunctive([0, point_task], [2, 0])
  elseif mode = 5 then
    occurs(point_task) /\ disjunctive_strict([0, point_task], [2, 0])
  elseif mode = 6 then
    occurs(overlap_task) /\ deopt(overlap_task) = 0 /\
    disjunctive([0, overlap_task], [1, duration])
  elseif mode = 7 then
    occurs(overlap_task) /\ deopt(overlap_task) = 0 /\
    (r <-> disjunctive([0, overlap_task], [1, 1])) /\ not r
  elseif mode = 8 then
    occurs(overlap_task) /\ deopt(overlap_task) = 0 /\
    not disjunctive([0, overlap_task], [1, 1])
  elseif mode = 9 then
    occurs(point_task) /\ disjunctive([<>, point_task], [2, 2])
  else
    disjunctive([if not p then 0 else <> endif, if not p then 1 else <> endif], [2, 2])
  endif;
solve satisfy;
output ["mode=", show(mode), "; absent_task=", show(absent_task),
        "; absent_value=", show(absent_value), "; overlap_task=", show(overlap_task),
        "; point_task=", show(point_task), "; duration=", show(duration),
        "; p=", show(p), "; r=", show(r), ";\n"];
