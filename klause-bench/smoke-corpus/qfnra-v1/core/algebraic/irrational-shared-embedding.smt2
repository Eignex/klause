; SPDX-License-Identifier: Apache-2.0
; Original fixture by Eignex Klause contributors.
(set-logic QF_NRA)
(set-info :status unsat)
(declare-const x Real)
(declare-const y Real)
(assert (and (= (* x x) 2) (> x 0) (= (* y y) 2) (> y 0) (distinct x y)))
(check-sat)
