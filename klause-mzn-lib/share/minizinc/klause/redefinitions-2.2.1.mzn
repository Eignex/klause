% Fixed nonnegative powers retain their source relation. Negative powers use
% MiniZinc integer division semantics and the standard nonzero-base requirement.
predicate int_pow_fixed(var int: x, int: y, var int: z) =
  if y >= 0 then klause_int_pow(x, y, z) else z = 1 div pow(x, -y) endif;
