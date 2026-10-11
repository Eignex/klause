; SPDX-License-Identifier: Apache-2.0
; Original fixture by Eignex Klause contributors.
(set-logic QF_NRA)
(set-info :status unsat)
(declare-const x Real)
(declare-const b Bool)
(assert (and (=> b (= (* x x) 2)) (=> b (< (* x x) 2))))
(check-sat-assuming (b))
