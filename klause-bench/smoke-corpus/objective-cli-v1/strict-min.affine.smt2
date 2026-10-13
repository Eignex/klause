(declare-const x Real) (assert (and (> x 0) (>= (* x x) 0))) (minimize (+ 7 x)) (check-sat)
