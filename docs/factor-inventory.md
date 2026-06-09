# Factor inventory (issue #209)

Inventory of every native propagator under
`klause/src/commonMain/kotlin/com/eignex/klause/solver/factor/`, classified for the
consolidation in #209. Lenses applied:

1. **Reachability** — can a library (Kotlin builder/schema/AST) user reach it, or is it
   FlatZinc-only / internal?
2. **Strength vs. decomposition** — does the native propagator beat emitting primitives?
   With the new CP→LP relaxation (fraction-free integer dual simplex, CP-SAT style)
   covering `Linear`/`Cardinality`/`PseudoBoolean`/`ReifiedLinear`/`ReifiedCardinality`/`Clause`,
   several arithmetic globals no longer carry their weight.
3. **Niche** — does any corpus / user actually exercise it, and is the maintenance surface
   (propagation + conflict reasons + LS support + 4 adapters + BitBlaster lowering)
   justified?

## Method notes

- Every genuine `Factor` class implements `LocalSearchFactor` (after the #250 fix that gave
  `SubsetSumEq` and `GaussianXor` redundant LS no-ops). The files that do *not* implement it
  are **not factors** — they are shared internal helpers and should not be counted as
  inventory: `CoeffLookup`, `LinearPbShared`, `ReginMatcher` (+`ReginCache`),
  `CumulativeThetaTree`, `MandatoryProfile`, `OptPresence`, and the test-only helper
  `IntCmpReified` (`reifiedIntCompare`, no production caller).
- LP relaxation rows are emitted only for: `Linear` (LE/GE/EQ; NE skipped), `Cardinality`,
  `PseudoBoolean`, `ReifiedLinear` (big-M), `ReifiedCardinality`, `Clause`.

## A. Keep — core primitives & LP-backed (no change)

`Linear`, `ReifiedLinear`, `Cardinality`, `ReifiedCardinality`, `PseudoBoolean`,
`ReifiedPseudoBoolean`, `Clause`, `Xor`, `Element`, `Product`.

These are the decomposition *targets*; they must stay and stay sharp. `Product` is currently
FlatZinc-only (`int_times`) but is a primitive — expose via builder.

## B. Strong globals — keep, and expose to the library API (#209 "strong" list)

| Factor | Lib API today | FZN | Why keep |
|---|---|---|---|
| GlobalCardinality | yes | yes | flow-deficiency reasons beat decomposition |
| Knapsack | **no (FZN-only)** | yes | DP profit/weight filter; expose |
| SubsetSumEq | yes | yes | DP reachability LP can't match at root |
| Cumulative | yes | yes | theta-tree edge-finding |
| Diffn | **no (FZN-only)** | yes | non-overlap; expose |
| NValue | yes | yes | strong counting bound |
| BinPacking | **no (FZN-only)** | yes | expose |
| Sequence | yes | yes | sliding cardinality |
| Among | **no (FZN-only)** | yes | expose |
| Count | yes | yes | |
| SymmetricAllDifferent | yes | yes | |
| GaussianXor | yes (count API) | yes | GF(2) — no clausal equivalent |
| Inverse | yes | yes | channeling |
| LexLess | yes | yes | symmetry breaking |
| ArrayMinMax | yes | yes | |
| AllDifferent | yes | yes | Régin matching |
| Circuit / Subcircuit | yes | yes | |
| Disjunctive | yes | yes | |
| Regular / Mdd | yes | yes | automaton/DD; strong vs. table blow-up |
| Table | yes | yes | |
| Sort | yes | yes | |
| MinCostFlow | builder-only | no | no FZN site, **no OR-Tools adapter** — finish or drop |

Action for B: the FZN-only ones (Knapsack, Diffn, BinPacking, Among, Product) need
builder + schema + AST + brute-equivalence tests so they stop being FlatZinc-only.

## C. Drop & decompose — weak, none user-facing (#209 PR1/PR2)

`#209`'s "weak" list. The ones that still exist as native classes:

| Factor | Replace with | Note |
|---|---|---|
| AllDifferentExcept | n capacity-1 value copies (already the reduction) | bounds-equal to decomposition |
| ArgMinMax | `array_*_max` + index channeling | LP/Element already as strong |
| ArgSort | `Sort` + permutation channeling | |

`all_equal`, `increasing/decreasing`, `member`, `sliding_sum`, `value_precede` have **no
dedicated factor class** — already emitted as primitives. Nothing to drop, only confirm the
emit sites stay primitive.

## D. Niche — candidates to cut now that LP exists (NEW, user lens)

Heavy maintenance surface, thin/zero corpus usage, and either redundant with LP+Linear or
with a sibling global. Recommend dropping the native propagator and decomposing:

| Factor | LOC | Why cut | Decompose to |
|---|---|---|---|
| Geost | 277 | k-dim generalization of Diffn; no corpus hits, no lib API | Diffn (2-D) / reject k>2 |
| Cumulatives | 706 | multi-machine cumulative; biggest single file, niche | per-machine `Cumulative` + assignment channel |
| SetBitsetAlgebra | 399 | set subset/disjoint/eq; set-var models are rare | bitset Boolean channel + `Cardinality` |
| PathTree (`Path`/`Tree`) | 267 | subsumed by `Circuit`/`Subcircuit` reachability | Circuit-family + reachability primitives |
| MinCostFlow | 378 | builder-only, no FZN, no OR-Tools adapter, untested at scale | `Linear` objective + flow-conservation rows (LP handles it) |

These five are the highest-ROI cuts: ~2.0 kLOC of propagation + conflict + LS + adapter +
BitBlaster surface removed, with LP/decomposition giving comparable bounds on the rare models
that use them.

## E. Internal helpers — not factors, leave as-is

`CoeffLookup`, `LinearPbShared`, `ReginMatcher`/`ReginCache`, `CumulativeThetaTree`,
`MandatoryProfile`, `OptPresence`. Shared by the real factors; not inventory items.
`IntCmpReified` (`reifiedIntCompare`) is a **test-only** helper — fold into the test util or
delete from `main`.
</content>
