; Self-authored composite source/model control. Apache-2.0.
(set-logic ALL)
(declare-datatype Packet ((packet (payload (_ BitVec 8)))))
(declare-const memory (Array Int Packet))
(assert (= (select memory 3) (packet #x2a)))
(check-sat)
