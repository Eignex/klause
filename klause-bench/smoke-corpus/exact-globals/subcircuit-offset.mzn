include "driver.mzn";
predicate subject(var int: x) =
  subcircuit(array1d(0..1, [1,x-1]));
