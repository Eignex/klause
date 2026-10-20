include "driver.mzn";
predicate subject(var int: x) =
  bin_packing_capa(array1d(0..2, [1,1,1]), [0,x], [1,1]);
