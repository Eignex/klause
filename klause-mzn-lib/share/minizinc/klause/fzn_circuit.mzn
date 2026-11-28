% Shadows std/fzn_circuit.mzn by basename. The opaque builtin reaches the native Circuit factor instead
% of an all_different plus subtour-elimination decomposition.
predicate klause_circuit(array[int] of var int: x);

predicate fzn_circuit(array[int] of var int: x) =
    klause_circuit([x[i] - min(index_set(x)) + 1 | i in index_set(x)]);
