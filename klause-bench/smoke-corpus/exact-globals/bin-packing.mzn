include "driver.mzn";
predicate subject(var int: x) = bin_packing(1, [0,x], [1,1]);
