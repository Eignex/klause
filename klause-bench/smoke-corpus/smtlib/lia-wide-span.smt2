; Self-authored QF_LIA smoke instance: columns bounded, but over a span wider than a Long can count,
; so nothing can enumerate them and every domain question must be answered from the bounds.
; The curated set otherwise holds no model of this shape. License: internal.
(set-logic QF_LIA)
(declare-const x Int)
(declare-const y Int)
(declare-const z Int)
(assert (>= x 0))
(assert (<= x 9223372036854775807))
(assert (>= y 0))
(assert (<= y 9223372036854775807))
(assert (>= z 0))
(assert (<= z 9223372036854775807))
(assert (> x y))
(assert (> y z))
(check-sat)
