package com.eignex.klause.bench.catalog

internal object QfNraSuites {
    val all: List<Suite> = listOf(
        suite("smtlib-qfnra-core-v1", "Frozen polynomial real core") {
            format = Format.SMTLIB
            license = "Apache-2.0 (self-authored Eignex Klause fixtures)"
            vendored(
                "algebraic/cubic-positive", expected = Expected.Sat,
                relPath = "qfnra-v1/core/algebraic/cubic-positive.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "algebraic/irrational-positive-section", expected = Expected.Sat,
                relPath = "qfnra-v1/core/algebraic/irrational-positive-section.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "algebraic/irrational-shared-embedding", expected = Expected.Unsat,
                relPath = "qfnra-v1/core/algebraic/irrational-shared-embedding.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "algebraic/irrational-tower", expected = Expected.Sat,
                relPath = "qfnra-v1/core/algebraic/irrational-tower.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "algebraic/quartic-no-real-root", expected = Expected.Unsat,
                relPath = "qfnra-v1/core/algebraic/quartic-no-real-root.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "boolean/equivalence-unsat", expected = Expected.Unsat,
                relPath = "qfnra-v1/core/boolean/equivalence-unsat.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "boolean/negated-equality", expected = Expected.Sat,
                relPath = "qfnra-v1/core/boolean/negated-equality.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "boolean/nontrivial-boolean-alternative", expected = Expected.Sat,
                relPath = "qfnra-v1/core/boolean/nontrivial-boolean-alternative.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "boolean/xor-sat", expected = Expected.Sat,
                relPath = "qfnra-v1/core/boolean/xor-sat.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "constants/constant-unsat", expected = Expected.Unsat,
                relPath = "qfnra-v1/core/constants/constant-unsat.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "constants/zero-sat", expected = Expected.Sat,
                relPath = "qfnra-v1/core/constants/zero-sat.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "degeneracy/degree-drop", expected = Expected.Sat,
                relPath = "qfnra-v1/core/degeneracy/degree-drop.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "degeneracy/equal-degree-common-root", expected = Expected.Sat,
                relPath = "qfnra-v1/core/degeneracy/equal-degree-common-root.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "degeneracy/irrational-degree-drop", expected = Expected.Sat,
                relPath = "qfnra-v1/core/degeneracy/irrational-degree-drop.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "degeneracy/nonzero-leading-zero-discriminant", expected = Expected.Sat,
                relPath = "qfnra-v1/core/degeneracy/nonzero-leading-zero-discriminant.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "degeneracy/positive-dimensional-nullification", expected = Expected.Sat,
                relPath = "qfnra-v1/core/degeneracy/positive-dimensional-nullification.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "degeneracy/repeated-section", expected = Expected.Sat,
                relPath = "qfnra-v1/core/degeneracy/repeated-section.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "degeneracy/shared-section", expected = Expected.Sat,
                relPath = "qfnra-v1/core/degeneracy/shared-section.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "degeneracy/zero-specialization", expected = Expected.Sat,
                relPath = "qfnra-v1/core/degeneracy/zero-specialization.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "degeneracy/zero-specialization-contradiction", expected = Expected.Unsat,
                relPath = "qfnra-v1/core/degeneracy/zero-specialization-contradiction.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "exact/huge-decimal-sat", expected = Expected.Sat,
                relPath = "qfnra-v1/core/exact/huge-decimal-sat.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "exact/huge-decimal-unsat", expected = Expected.Unsat,
                relPath = "qfnra-v1/core/exact/huge-decimal-unsat.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "exact/wide-exact-rational", expected = Expected.Sat,
                relPath = "qfnra-v1/core/exact/wide-exact-rational.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "multivariate/elimination-contradiction", expected = Expected.Unsat,
                relPath = "qfnra-v1/core/multivariate/elimination-contradiction.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "multivariate/lower-dimensional-intersection", expected = Expected.Sat,
                relPath = "qfnra-v1/core/multivariate/lower-dimensional-intersection.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "multivariate/singular-repeated-curve", expected = Expected.Sat,
                relPath = "qfnra-v1/core/multivariate/singular-repeated-curve.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "strict/irrational-boundary-unsat", expected = Expected.Unsat,
                relPath = "qfnra-v1/core/strict/irrational-boundary-unsat.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "strict/strict-between-roots", expected = Expected.Sat,
                relPath = "qfnra-v1/core/strict/strict-between-roots.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "strict/strict-root-contradiction", expected = Expected.Unsat,
                relPath = "qfnra-v1/core/strict/strict-root-contradiction.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "strict/strict-shared-boundary", expected = Expected.Unsat,
                relPath = "qfnra-v1/core/strict/strict-shared-boundary.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "syntax/assumption-unsat", expected = Expected.Unsat,
                relPath = "qfnra-v1/core/syntax/assumption-unsat.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "syntax/chained-comparison-unsat", expected = Expected.Unsat,
                relPath = "qfnra-v1/core/syntax/chained-comparison-unsat.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "syntax/constant-division-sat", expected = Expected.Sat,
                relPath = "qfnra-v1/core/syntax/constant-division-sat.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "syntax/lexical-define-fun", expected = Expected.Sat,
                relPath = "qfnra-v1/core/syntax/lexical-define-fun.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "syntax/parallel-let-sat", expected = Expected.Sat,
                relPath = "qfnra-v1/core/syntax/parallel-let-sat.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "syntax/power-sat", expected = Expected.Sat,
                relPath = "qfnra-v1/core/syntax/power-sat.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
            vendored(
                "syntax/real-ite-sat", expected = Expected.Sat,
                relPath = "qfnra-v1/core/syntax/real-ite-sat.smt2",
                tags = setOf("polynomial-real", "qfnra-core-v1"),
            )
        },
        suite("smtlib-qfnra-holdout-v1", "Frozen polynomial real holdout") {
            format = Format.SMTLIB
            license = "Apache-2.0 (self-authored Eignex Klause fixtures)"
            vendored(
                "boolean/branch-3-sat", expected = Expected.Sat,
                relPath = "qfnra-v1/holdout/boolean/branch-3-sat.smt2",
                tags = setOf("polynomial-real", "qfnra-holdout-v1"),
            )
            vendored(
                "boolean/branch-3-unsat", expected = Expected.Unsat,
                relPath = "qfnra-v1/holdout/boolean/branch-3-unsat.smt2",
                tags = setOf("polynomial-real", "qfnra-holdout-v1"),
            )
            vendored(
                "boolean/branch-5-sat", expected = Expected.Sat,
                relPath = "qfnra-v1/holdout/boolean/branch-5-sat.smt2",
                tags = setOf("polynomial-real", "qfnra-holdout-v1"),
            )
            vendored(
                "boolean/branch-5-unsat", expected = Expected.Unsat,
                relPath = "qfnra-v1/holdout/boolean/branch-5-unsat.smt2",
                tags = setOf("polynomial-real", "qfnra-holdout-v1"),
            )
            vendored(
                "boolean/branch-7-sat", expected = Expected.Sat,
                relPath = "qfnra-v1/holdout/boolean/branch-7-sat.smt2",
                tags = setOf("polynomial-real", "qfnra-holdout-v1"),
            )
            vendored(
                "boolean/branch-7-unsat", expected = Expected.Unsat,
                relPath = "qfnra-v1/holdout/boolean/branch-7-unsat.smt2",
                tags = setOf("polynomial-real", "qfnra-holdout-v1"),
            )
            vendored(
                "circle/intersection-3-sat", expected = Expected.Sat,
                relPath = "qfnra-v1/holdout/circle/intersection-3-sat.smt2",
                tags = setOf("polynomial-real", "qfnra-holdout-v1"),
            )
            vendored(
                "circle/intersection-5-sat", expected = Expected.Sat,
                relPath = "qfnra-v1/holdout/circle/intersection-5-sat.smt2",
                tags = setOf("polynomial-real", "qfnra-holdout-v1"),
            )
            vendored(
                "circle/intersection-7-sat", expected = Expected.Sat,
                relPath = "qfnra-v1/holdout/circle/intersection-7-sat.smt2",
                tags = setOf("polynomial-real", "qfnra-holdout-v1"),
            )
            vendored(
                "circle/outside-3-unsat", expected = Expected.Unsat,
                relPath = "qfnra-v1/holdout/circle/outside-3-unsat.smt2",
                tags = setOf("polynomial-real", "qfnra-holdout-v1"),
            )
            vendored(
                "circle/outside-5-unsat", expected = Expected.Unsat,
                relPath = "qfnra-v1/holdout/circle/outside-5-unsat.smt2",
                tags = setOf("polynomial-real", "qfnra-holdout-v1"),
            )
            vendored(
                "circle/outside-7-unsat", expected = Expected.Unsat,
                relPath = "qfnra-v1/holdout/circle/outside-7-unsat.smt2",
                tags = setOf("polynomial-real", "qfnra-holdout-v1"),
            )
            vendored(
                "singular/nullified-3-sat", expected = Expected.Sat,
                relPath = "qfnra-v1/holdout/singular/nullified-3-sat.smt2",
                tags = setOf("polynomial-real", "qfnra-holdout-v1"),
            )
            vendored(
                "singular/nullified-3-unsat", expected = Expected.Unsat,
                relPath = "qfnra-v1/holdout/singular/nullified-3-unsat.smt2",
                tags = setOf("polynomial-real", "qfnra-holdout-v1"),
            )
            vendored(
                "singular/nullified-5-sat", expected = Expected.Sat,
                relPath = "qfnra-v1/holdout/singular/nullified-5-sat.smt2",
                tags = setOf("polynomial-real", "qfnra-holdout-v1"),
            )
            vendored(
                "singular/nullified-5-unsat", expected = Expected.Unsat,
                relPath = "qfnra-v1/holdout/singular/nullified-5-unsat.smt2",
                tags = setOf("polynomial-real", "qfnra-holdout-v1"),
            )
            vendored(
                "singular/nullified-7-sat", expected = Expected.Sat,
                relPath = "qfnra-v1/holdout/singular/nullified-7-sat.smt2",
                tags = setOf("polynomial-real", "qfnra-holdout-v1"),
            )
            vendored(
                "singular/nullified-7-unsat", expected = Expected.Unsat,
                relPath = "qfnra-v1/holdout/singular/nullified-7-unsat.smt2",
                tags = setOf("polynomial-real", "qfnra-holdout-v1"),
            )
            vendored(
                "tower/tower-3-sat", expected = Expected.Sat,
                relPath = "qfnra-v1/holdout/tower/tower-3-sat.smt2",
                tags = setOf("polynomial-real", "qfnra-holdout-v1"),
            )
            vendored(
                "tower/tower-3-unsat", expected = Expected.Unsat,
                relPath = "qfnra-v1/holdout/tower/tower-3-unsat.smt2",
                tags = setOf("polynomial-real", "qfnra-holdout-v1"),
            )
            vendored(
                "tower/tower-5-sat", expected = Expected.Sat,
                relPath = "qfnra-v1/holdout/tower/tower-5-sat.smt2",
                tags = setOf("polynomial-real", "qfnra-holdout-v1"),
            )
            vendored(
                "tower/tower-5-unsat", expected = Expected.Unsat,
                relPath = "qfnra-v1/holdout/tower/tower-5-unsat.smt2",
                tags = setOf("polynomial-real", "qfnra-holdout-v1"),
            )
            vendored(
                "tower/tower-7-sat", expected = Expected.Sat,
                relPath = "qfnra-v1/holdout/tower/tower-7-sat.smt2",
                tags = setOf("polynomial-real", "qfnra-holdout-v1"),
            )
            vendored(
                "tower/tower-7-unsat", expected = Expected.Unsat,
                relPath = "qfnra-v1/holdout/tower/tower-7-unsat.smt2",
                tags = setOf("polynomial-real", "qfnra-holdout-v1"),
            )
        },
        suite("smtlib-qfnra-extensions-v1", "Explicit nonpolynomial, mixed-sort and session controls") {
            format = Format.SMTLIB
            license = "Apache-2.0 (self-authored Eignex Klause fixtures)"
            vendored(
                "division/symbolic-divisor", expected = Expected.Unknown,
                relPath = "qfnra-v1/extensions/division/symbolic-divisor.smt2",
                tags = setOf("division-by-variable", "qfnra-extensions-v1"),
            )
            vendored(
                "mixed/integer-real", expected = Expected.Unknown,
                relPath = "qfnra-v1/extensions/mixed/integer-real.smt2",
                tags = setOf("mixed-integer", "qfnra-extensions-v1"),
            )
            vendored(
                "nonpolynomial/negative-power", expected = Expected.Unknown,
                relPath = "qfnra-v1/extensions/nonpolynomial/negative-power.smt2",
                tags = setOf("negative-power", "qfnra-extensions-v1"),
            )
            vendored(
                "nonpolynomial/transcendental", expected = Expected.Unknown,
                relPath = "qfnra-v1/extensions/nonpolynomial/transcendental.smt2",
                tags = setOf("transcendental", "qfnra-extensions-v1"),
            )
            vendored(
                "session/multiple-queries", expected = Expected.Unknown,
                relPath = "qfnra-v1/extensions/session/multiple-queries.smt2",
                tags = setOf("multiple-queries", "qfnra-extensions-v1"),
            )
        },
    )
}
