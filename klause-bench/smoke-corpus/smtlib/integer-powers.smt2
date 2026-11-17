; Self-authored SMT-LIB 2.7 integer exponent conventions. Apache-2.0.
(set-logic QF_EIA)
(assert (= (** 0 0) 1))
(assert (= (** (- 2) (- 3)) 0))
(assert (= (** (- 1) (- 3)) (- 1)))
(check-sat)
