% Override std/fzn_circuit.mzn. MiniZinc looks up a solver-lib file with the same basename
% as a std file and uses it instead. Forward to klause's namespaced `klause_circuit`
% builtin (opaque to MiniZinc, so passed through verbatim rather than decomposed into
% all_different + order-variable subtour elimination), which klause's front-end maps to the
% native Circuit factor — graded Hamiltonian-cycle violation + cycle-merge repair moves,
% the move set successor-encoded routing (cvrp, tsp) needs.
predicate klause_circuit(array[int] of var int: x);

predicate fzn_circuit(array[int] of var int: x) =
    klause_circuit(x);
