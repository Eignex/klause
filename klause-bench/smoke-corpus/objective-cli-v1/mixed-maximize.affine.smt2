(declare-const i Int) (declare-const x Real) (declare-const y Real)
(assert (and (>= i 0) (<= i 1))) (assert (= x 1))
(assert (= (* (to_real i) x) y))
(maximize (+ 7 y)) (check-sat)
