; SPDX-License-Identifier: Apache-2.0
; Original fixture by Eignex Klause contributors.
(set-logic QF_NRA)
(set-info :status unsat)
(declare-const x Real)
(assert (and (= (* x x) 2) (> x 0) (< x (/ 7 5))))
(check-sat)
