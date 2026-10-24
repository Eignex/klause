# Source equality blocks

Self-authored Eignex fixtures, Apache-2.0. `source-equalities-v1` retains exact
real, rational-coefficient, mixed integer/real, wide-integer and polynomial-backed
source blocks. All are satisfiable except `real-inconsistent`, whose three
linear equations contradict. `guarded-alternative` is satisfiable with `b=true`;
its alternative equality is not an unconditional premise.

The first two equalities imply the third in each dependent fixture. The wide
fixture's leading coefficient is 2^80. These small controls test representation
capability; they do not predict a search benefit on larger sources.

Original source SHA-256 values:
fad6517e75e7bdb7ce2cd90a79bc9d4892150b548f4cfd0cc392e882c8385f9d  klause-bench/smoke-corpus/source-equalities-v1/fractional-dependent.smt2
06ca72a5c977ba130cf8b9eef35592203f959936c3d197d5924020d3f78b303c  klause-bench/smoke-corpus/source-equalities-v1/guarded-alternative.smt2
1a416d7f3343ff301a05b55fb5c22897628e619dc751b821236ce7320f56bcf5  klause-bench/smoke-corpus/source-equalities-v1/mixed-dependent.smt2
ed9027a37b64c8aacbb014d505992eaf99fca219b47f951e1e68fcfb647396b0  klause-bench/smoke-corpus/source-equalities-v1/polynomial-dependent.smt2
59b28312e1c788480d2d2e87a465679a7ab8458049c6bb1b7eece50793874dab  klause-bench/smoke-corpus/source-equalities-v1/real-dependent.smt2
37057212ded315c426c49d32cbab9f30bbdc7af7249a3e89d0fb4a95894222c3  klause-bench/smoke-corpus/source-equalities-v1/real-inconsistent.smt2
b898f8db07439ae6855ac598072d4e1470967848b0e2a25fdb2e55df0e070861  klause-bench/smoke-corpus/source-equalities-v1/wide-dependent.smt2
