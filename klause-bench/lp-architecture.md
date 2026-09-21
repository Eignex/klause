# LP architecture and migration boundary

The float implementation is [RevisedSimplex][simplex]. [ProductionLpEngineFactory][factory] is the
production default; the explicit `UnscaledLpEngineFactory` rollback constructs the same implementation
with scaling disabled. Legacy component splitting composes that engine for OBBT. It does not provide
an alternative native engine. [LpReferenceAdapter][reference] is an independent test-only reference.

## Kernel boundaries

| Package | Responsibility and permitted outbound kernel dependencies |
|---|---|
| `lp.engine` | Float solving and exact certification; `util`, `simplex.exact`, `lp.lattice`, `simplex.basis`. |
| `lp.lattice` | Exact integer matrix reduction and triangular bounds; `util`. |
| `simplex.exact` | Rational arithmetic and feasibility continuation; `util`. |
| `simplex.basis` | Exact basis factors; `util` and neutral `BigFraction`, `Frac128`, `Frac128Ops` numeric types. |

Model assembly, factors, propagation, search and presolve depend on these mechanisms; the engine
cannot depend on them. The [structural test][fence] scans qualified references in imports, code and
KDoc. The root rules include the existing `simplex.basis` seam; this is not a new runtime dependency.
The factory's documentation does not require an engine-to-policy reference.

[Koblas pins][pins] retain the timestamped root `0.1.1-20260918.215912-232` and strict platform pins:
JVM 235, Linux x64 231, Linux arm64 232 and macOS arm64 240. The existing klause-on-koblas matrix,
vector and basis operations remain. This migration changes neither those kernels nor the ordinary
problem-level presolve/reconstruction path.

## Bound updates and replay

[PersistentLpSolver][interface] exposes exact-state adoption and bound resolution.
[CpLpAdapter][cp] and [LpScopedSolver][scoped] retain the source bound trail, active-row identity,
source/working authority, basis factors and exact verification caches. Structural appends and
compaction use fresh fixed-dimension owners with existing transaction and cleanup rules.

The two legacy persistent bound-update callers have distinct replacements:

- [LpBounding.solveNode][bounding] imports eligible finite source models into the scoped path.
  Failure to import exact authority retains the bounded fresh legacy fallback and its warm hint,
  cancellation, per-call limits, metrics and cleanup semantics. A later scoped projection decline
  does not enter that fallback. The fallback does not retain factors between calls.
- [LpReplay][replay] interprets legacy capture `REBIND` events using `LpModel.rebind` for checked
  source-coordinate copies and exact-state adoption on one persistent float owner. Captures without
  `REBIND` retain their existing route. The wire format and ordered events remain unchanged.
  A parseable capture with a negative logical upper width is not valid exact authority: a capture
  containing `REBIND` rejects it before owner allocation. This invalid-authority compatibility
  change does not broaden the wire parser's rejection rules.

Replay bound replacements carry independent source authority and increasing revisions, including
coordinate shifts that change the objective constant. Certification receives the exact state instance
adopted by the solver; the independent validator receives the original source model. Probe flags
preserve each flagged side's absence even when a raw bound event writes finite coordinates there;
they do not remove the opposite unflagged side. Legacy wire warm statuses can be rejected by the
native solver when they refer to an absent side, causing cold repair rather than guaranteed warmth.
A mixed gated event temporarily deactivates logical-row bounds and calls `resolveBounds`;
`resolveGated` remains a separate live legacy seam. Its float hint remains uncertified; an ordinary
event restores full source rows before certification. A failed adoption poisons subsequent solves
until a successful explicit replacement: no donor result, ray, metrics or exact continuation claim
may escape under the requested state. Explicit cancellation reset events retain their meaning.

The capture owns one cumulative exact-continuation allowance across replacements. Adoption can
change float paths, cancellation polling and work; one owner does not mean identical pivots or
absence of exact continuation factors. Generated native basis statuses are not promised as portable
legacy recaptures. Input discrete warm bases remain supported. Adoption projects working vectors
inside the solver and again for replay certification; matrix projection is shared, but the repeated
vector allocation and source-import preparation are not included in float work counters.
Conservative pop revisions on replacements invalidate cached basic values; retaining factors does
not preserve that restoration shortcut and can require a full forward solve.

`LpModel.rebind` remains a model-copy operation. `ResumableMinimize.rebind` remains a separate search
repair lifecycle, outside this migration. Neither is an obsolete persistent engine adapter.

## Evidence and retained obligations

The coordinator's local `session-7.6` evidence bundle records the frozen contract, including base
`20b12fd227fe5160f5d69e600a2a2d12d9a4813a`, allocated files, assertion migrations and independent
Opus review. The small deterministic source traces compare source proofs, owner closure, work and
factor counts under fixed limits. They do not establish whole-plan acceptance or total performance.

On the noncanonical CSC fallback control, exact import deliberately declines while the float hint
matches the analytic source objective. Four bound calls change one retained owner into four fresh
owners, all closed; factor warmth is lost. No natural producer or certified capability is claimed
for this malformed-input fixture. Constructor/projection and direct-certification setup are not
fully represented by float work counters, so their omission cannot support a speedup claim.

Normal replay controls preserve source packages. At work limit 10, 25 ordinary steps require an
independent constant-objective certificate to preserve the legacy bound alongside the exact native
continuation witness. [Certification][certification] checks every authoritative structural and logical
cost, then uses the existing exact zero-multiplier Lagrangian. Its support belongs to the current
state, and policy rejection and callback-time cancellation can withhold it. Gated and poisoned replay
steps never enter certification. The fixed-cap comparison preserves all measured source bounds;
the 25 repaired records carry both bound and witness. Low-limit float work increases on some traces.

The shared certification branch also applies to constant-objective scoped production relaxations,
in their own minimized objective units. `ResumableMinimize.IncumbentPolicy` treats attained real
leaves as resolved: a newly complete witness-and-bound package can therefore permit ordinary leaf
blocking where a witness alone kept the search indeterminate. The residual LP rechecks its exact
witness against the assembled relaxation; incumbent admission checks finite objective and real-value
coverage, not arbitrary full-factor source reconstruction. F's unresolved-leaf stop-before-block
protection and the broader source/consumer acceptance obligations remain required. A directed
[producer regression][consumer-test] checks the fully covered source `2r = 1`, `r in [0, 1]`, objective
zero: real resource exit and exact continuation resolve the leaf only when the independent bound is
accepted; withholding it preserves `Unsupported`, terminal idempotence and no shared clauses.
The bound can also reach `strictSourcePrune`; broader pruning and consumer-corpus acceptance remain
unmeasured. Its two matrix-list-copy passes and exact arithmetic follow
the existing direct-certification policy outside float counters and continuation allowances. The
certificate computation is not internally cancellation-polled; boundary checks prevent publication
after cancellation. This does not establish parity for general nonconstant truncated candidates or
cancellation poll schedules. A valid witness alone never substitutes for a lost bound.

[Retention decisions][retention] govern the mechanisms that remain: owner-local exact factors,
bounded refinement and cumulative resource ledgers, complete witness-and-bound counter-proofs,
required trail/objective incrementality, current zero-objective pricing, exact term sharing/root
substitution, bounded first-root crash and legacy OBBT splitting. Optional epochs/tidying,
dualization, factor transfer/snapshots, perturbation and extra recovery are not live strategies.
Cold production exact LP entry points, private LP integer DFS and capped determinant certifiers are
retired. Exact point/direction checks, rational basis verification/continuation and integer lattice
reduction are retained mechanisms, not those retired solvers.

The following obligations remain separate from migration, a passing gate and the small controls:

- Raw search repair returns −37 versus independently established −38 in the saved reproducer.
- Decimal/source objective and rendered-assignment validation failures remain open.
- Wave 2 captured exact-check/bound-update and work/factor acceptance remain unestablished.
- Wave 3 clause/cut/backjump and consumer acceptance remain unestablished.
- Wave 4 bounded certification-rate/time acceptance remains unestablished.
- `perold`/`25fv47` reference disagreements and `80bau3b` capability remain open. F repairs a false
  UNSAT promotion to UNKNOWN; it does not recover source feasibility or optimality.
- C3's bound-versus-unbounded guard is source-inspected; a dedicated adversarial recession-getter
  regression is still an explicit gap.

[simplex]: ../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/RevisedSimplex.kt
[factory]: ../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/LpEngineFactory.kt
[interface]: ../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/LpSolver.kt
[reference]: ../klause/src/jvmTest/kotlin/com/eignex/klause/lp/engine/LpReferenceAdapter.kt
[fence]: ../klause/src/jvmTest/kotlin/com/eignex/klause/lp/LpDeclineDisciplineTest.kt
[pins]: ../klause/build.gradle.kts
[cp]: ../klause/src/commonMain/kotlin/com/eignex/klause/lp/bounding/CpLpAdapter.kt
[scoped]: ../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/LpScopedSolver.kt
[bounding]: ../klause/src/commonMain/kotlin/com/eignex/klause/lp/bounding/LpBounding.kt
[replay]: ../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/LpReplay.kt
[retention]: lp-retention.md
[certification]: ../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/LpSolve.kt
[consumer-test]: ../klause/src/commonTest/kotlin/com/eignex/klause/backtrack/ResumableMinimizeTest.kt
