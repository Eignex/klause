package com.eignex.klause.bench.catalog

internal object NonlinearIntegerSuite {
    val suite: Suite = suite("smtlib-nonlinear-integer-v1", "Polynomial integer and mixed source regressions") {
        format = Format.SMTLIB
        license = "Apache-2.0 (self-authored Eignex Klause fixtures)"
            vendored("bounded-product-sat", expected = Expected.Sat,
                relPath = "nonlinear-integer-v1/bounded-product-sat.smt2", tags = setOf("nonlinear-integer"),
            )
            vendored("bounded-product-unsat", expected = Expected.Unsat,
                relPath = "nonlinear-integer-v1/bounded-product-unsat.smt2", tags = setOf("nonlinear-integer"),
            )
            vendored("bounded-power-chain", expected = Expected.Sat,
                relPath = "nonlinear-integer-v1/bounded-power-chain.smt2", tags = setOf("nonlinear-integer"),
            )
            vendored("mixed-finite-product", expected = Expected.Sat,
                relPath = "nonlinear-integer-v1/mixed-finite-product.smt2", tags = setOf("nonlinear-integer"),
            )
            vendored("mixed-product-power", expected = Expected.Sat,
                relPath = "nonlinear-integer-v1/mixed-product-power.smt2", tags = setOf("nonlinear-integer"),
            )
            vendored("boolean-product-conditional", expected = Expected.Sat,
                relPath = "nonlinear-integer-v1/boolean-product-conditional.smt2", tags = setOf("nonlinear-integer"),
            )
            vendored("lexical-integer-helper", expected = Expected.Sat,
                relPath = "nonlinear-integer-v1/lexical-integer-helper.smt2", tags = setOf("nonlinear-integer"),
            )
            vendored("wide-integer-square", expected = Expected.Sat,
                relPath = "nonlinear-integer-v1/wide-integer-square.smt2", tags = setOf("nonlinear-integer"),
            )
            vendored("overflow-cancellation", expected = Expected.Sat,
                relPath = "nonlinear-integer-v1/overflow-cancellation.smt2", tags = setOf("nonlinear-integer"),
            )
            vendored("open-integer-product", expected = Expected.Sat,
                relPath = "nonlinear-integer-v1/open-integer-product.smt2", tags = setOf("nonlinear-integer"),
            )
            vendored("open-no-integer-root", expected = Expected.Unsat,
                relPath = "nonlinear-integer-v1/open-no-integer-root.smt2", tags = setOf("nonlinear-integer"),
            )
            vendored("whole-real-refutation", expected = Expected.Unsat,
                relPath = "nonlinear-integer-v1/whole-real-refutation.smt2", tags = setOf("nonlinear-integer"),
            )
    }
}
