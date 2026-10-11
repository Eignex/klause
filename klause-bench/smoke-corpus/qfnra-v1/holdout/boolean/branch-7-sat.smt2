; SPDX-License-Identifier: Apache-2.0
; Original fixture by Eignex Klause contributors.
(set-logic QF_NRA)
(set-info :status sat)
(declare-const x Real)
(declare-const y Real)
(assert (or (= (+ (* x x) 7) 0) (and (= (* x y) 7) (< x 0) (< y 0))))
(check-sat)
