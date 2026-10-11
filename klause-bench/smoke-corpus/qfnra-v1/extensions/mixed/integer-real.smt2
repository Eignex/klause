; SPDX-License-Identifier: Apache-2.0
; Original fixture by Eignex Klause contributors.
(set-logic QF_NIRA)
(set-info :status unsat)
(declare-const x Real)
(declare-const i Int)
(assert (and (= (* x x) 2) (= (to_real i) x)))
(check-sat)
