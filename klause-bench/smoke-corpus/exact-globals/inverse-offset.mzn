include "driver.mzn";
predicate subject(var int: x) =
  inverse(array1d(0..0, [x-1]), array1d(0..0, [0]));
