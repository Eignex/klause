; SPDX-License-Identifier: Apache-2.0
; Original fixture by Eignex Klause contributors.
(set-logic QF_NRA)
(set-info :status unsat)
(declare-const x Real)
(declare-const y Real)
(assert (or (= (+ (* x x) 3) 0) (and (= (* x y) 3) (< x 0) (> y 0))))
(check-sat)
