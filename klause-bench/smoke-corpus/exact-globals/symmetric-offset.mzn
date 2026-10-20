include "driver.mzn";
predicate subject(var int: x) =
  symmetric_all_different(array1d(0..1, [1,x-1]));
