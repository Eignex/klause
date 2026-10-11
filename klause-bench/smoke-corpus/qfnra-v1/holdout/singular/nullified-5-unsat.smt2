; SPDX-License-Identifier: Apache-2.0
; Original fixture by Eignex Klause contributors.
(set-logic QF_NRA)
(set-info :status unsat)
(declare-const x Real)
(declare-const y Real)
(declare-const z Real)
(assert (and (= (* x x) 5) (= (* (- (* x x) 5) y z) 1)))
(check-sat)
