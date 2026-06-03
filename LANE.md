# Lane: lexless (#75)

Scope: LexLess factor — conflict-analysis antecedents.

Single issue:
- #75 Bug: LexLess per-tighten antecedents omit the fixed-equal prefix (unsound learned clause). At the first non-singleton index i, LexLess.kt (~lines 231-234) tightens xs[i].max <= ys[i].max and ys[i].min >= xs[i].min but cites antecedents derived only from xs[i]/ys[i]. The deduction depends on the entire fixed-equal prefix xs[0..i-1] = ys[0..i-1], so learned clauses built from these per-tighten antecedents are missing the prefix-equality literals — a too-weak reason that can exclude feasible assignments. The conflict reason at LexLess.kt:179 is already broad (all of xs and ys); the gap is only in the per-tighten antecedents.

Fix: include the prefix bound atoms of xs[0..i-1]/ys[0..i-1] in antFromX/antFromY, or fall back to citing all earlier indices. Add a targeted CDCL test on a lex-constrained model that exercises a backjump through a per-tighten LexLess deduction.

Build/CI: `./gradlew detektMainJvm` is enforced by `build` (not the plain `detekt` task); the format-on-edit hook can leave dangling semicolons — review the diff before committing.
