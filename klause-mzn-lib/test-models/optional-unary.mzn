include "globals.mzn";

var 0..2: a;
var 0..2: b;
var 0..2: c;
var bool: p;
var opt 0..2: task = if p then c else <> endif;
constraint disjunctive([a, b, task], [2, 2, 2]);
solve satisfy;
output ["a=", show(a), "; b=", show(b), "; c=", show(c),
        "; p=", show(p), "; task=", show(task), ";\n"];
