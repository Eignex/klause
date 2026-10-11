; SPDX-License-Identifier: Apache-2.0
; Original fixture by Eignex Klause contributors.
(set-logic ALL)
(set-info :status sat)
(declare-const x Real)
(assert (= (sin x) 0))
(check-sat)
