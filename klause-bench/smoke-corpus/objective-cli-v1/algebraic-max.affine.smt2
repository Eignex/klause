(declare-const x Real) (assert (and (= (* x x) 2) (> x 0))) (maximize (+ 7 x)) (check-sat)
