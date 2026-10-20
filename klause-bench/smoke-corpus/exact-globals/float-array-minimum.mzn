include "driver.mzn";
predicate subject(var int: x) = array_float_minimum(1.0, [2.0,int2float(x)]);
