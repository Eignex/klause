; SPDX-License-Identifier: Apache-2.0
; Original fixture by Eignex Klause contributors.
(set-logic QF_NRA)
(set-info :status sat)
(declare-const x Real)
(declare-const y Real)
(assert (and (= (* x x) 2) (> x 0) (= (+ (* (- (* x x) 2) y y) y (- 1)) 0)))
(check-sat)
