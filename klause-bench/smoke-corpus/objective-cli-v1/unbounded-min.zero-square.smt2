(declare-const x Real) (assert (>= (* x x) 0)) (minimize (+ (+ 7 x) (* 0 x x))) (check-sat)
