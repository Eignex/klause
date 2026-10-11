; SPDX-License-Identifier: Apache-2.0
; Original fixture by Eignex Klause contributors.
(set-logic QF_NRA)
(set-info :status unsat)
(declare-const x Real)
(declare-const y Real)
(assert (and (= x 0) (= (* x y) 1)))
(check-sat)
