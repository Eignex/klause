(declare-const x Real) (assert (>= (* x x) 0)) (minimize (+ 7 x)) (check-sat)
