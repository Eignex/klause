include "globals.mzn";

int: coupling;
int: claim;
predicate subject(var int: x);
var 0..2: x;
var 0.0..2.0: r;
var 0.0..2.0: y;
constraint subject(x);
constraint if coupling = 0 then r = 0.0 /\ y = 0.0
  else r = int2float(x) /\
    if coupling = 1 then 3.0 * y = r else y * y = r endif
  endif;
constraint if claim = 0 then x = 1 elseif claim = 1 then
  if coupling = 0 then x = 0 else r = 0.0 endif
  else true endif;
solve minimize if coupling = 2 then y else int2float(x) endif;
output ["x = ", show(x), ";\n"];
