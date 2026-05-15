% 8-queens, classical CP smoke test. Exercises:
% - int_var declarations with bounded domains
% - all_different_int (native to klause)
% - all_different over offsets (decomposed by MiniZinc's std lib into int_lin / int_ne)
% - solve satisfy with a count
%
% Run: minizinc --solver com.eignex.klause queens.mzn -a -n 10

int: n = 8;
array[1..n] of var 1..n: q;

constraint all_different(q);
constraint all_different([q[i] + i | i in 1..n]);
constraint all_different([q[i] - i | i in 1..n]);

solve satisfy;

output [ "q = ", show(q), "\n" ];
