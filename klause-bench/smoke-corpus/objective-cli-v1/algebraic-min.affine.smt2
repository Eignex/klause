(declare-const x Real) (assert (and (= (* x x) 2) (> x 0))) (minimize (+ 7 x)) (check-sat)
