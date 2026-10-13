(declare-const x Real) (assert (= (* x x) (- 1))) (minimize (+ 7 x)) (check-sat)
