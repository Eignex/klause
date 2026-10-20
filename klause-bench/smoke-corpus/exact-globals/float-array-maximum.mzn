include "driver.mzn";
predicate subject(var int: x) = array_float_maximum(1.0, [0.0,int2float(x)]);
