; Self-authored wide-word function/array control. Apache-2.0.
(set-logic QF_AUFBV)
(declare-fun key ((_ BitVec 129)) (_ BitVec 129))
(declare-const memory (Array (_ BitVec 129) (_ BitVec 8)))
(assert (= (key (_ bv1 129)) (_ bv2 129)))
(assert (= (select memory (key (_ bv1 129))) #x2a))
(assert (= (select memory (_ bv2 129)) #x2a))
(check-sat)
