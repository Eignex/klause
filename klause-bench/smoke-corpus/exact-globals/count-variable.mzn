include "driver.mzn";
predicate subject(var int: x) = count_eq([1,1], x, 2);
