; SPDX-License-Identifier: Apache-2.0
; Original fixture by Eignex Klause contributors.
(set-logic QF_NRA)
(set-info :status sat)
(declare-const x Real)
(assert (or (= (+ (* x x) 1) 0) (= (* x x) 2)))
(check-sat)
