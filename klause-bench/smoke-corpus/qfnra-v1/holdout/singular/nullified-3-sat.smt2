; SPDX-License-Identifier: Apache-2.0
; Original fixture by Eignex Klause contributors.
(set-logic QF_NRA)
(set-info :status sat)
(declare-const x Real)
(declare-const y Real)
(declare-const z Real)
(assert (and (= (* x x) 3) (> x 0) (= (* (- (* x x) 3) y z) 0) (> y 1) (< z (- 1))))
(check-sat)
