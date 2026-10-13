(declare-const x Real) (assert (and (> x 0) (>= (* x x) 0))) (maximize (+ (- 7 x) (* 0 x x))) (check-sat)
