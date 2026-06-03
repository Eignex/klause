# Lane: factors-matching

Scope: AllDifferent, AllDifferentExcept, AllDifferentExceptZero, SymmetricAllDifferent, GlobalCardinality, Inverse, ArgSort, plus the weak counting/sequencing/membership globals (Among, Count, NValue, ValuePrecede, Member) and AllEqual.

Issues in order:
1. #102 Test: run the GAC-claimed factors at full strength (exactProbe) with holey domains. DO FIRST — it is the only completeness regression guard for everything else in this lane and the Régin part of #99. It is also a latent-bug probe: it may FAIL on current code (the prior false-UNSAT was a holey-domain bug).
2. #82 Bug: AllEqual bounds-only intersection drops holes. After #102. Hinges on an open IntDomain contract question — whether tightenIntMin/tightenIntMax silently accept a hole; resolve that first (see #82 body).
3. #87 Algorithm: strengthen Among / Count / NValue / ValuePrecede / Member. After #102.
4. #96 (matching part) make the AllDifferent-family / Inverse / ArgSort matching incremental. The Diffn part is in factors-scheduling.
5. #99 (Régin part) extract a shared ReginMatcher across AllDifferent / AllDifferentExcept / GlobalCardinality. The time-tabling part is in factors-scheduling.

Cross-lane: #99 (time-tabling → factors-scheduling), #96 (Diffn → factors-scheduling).

Build/CI: `./gradlew detektMainJvm` is enforced by `build`; the format-on-edit hook can leave dangling semicolons — review the diff before committing.
