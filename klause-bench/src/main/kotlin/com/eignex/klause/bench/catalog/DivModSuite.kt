package com.eignex.klause.bench.catalog

internal object DivModSuite {
    val suite = suite("divmod", "Integer quotient and remainder source relations") {
        format = Format.SMTLIB
        license = "internal"
        for (name in listOf("euclidean", "euclidean-linear", "wide")) {
            vendored(name, Category.CSP, Expected.Sat, relPath = "divmod/$name.smt2")
        }
        vendored("joint-conflict", Category.UNSAT, Expected.Unsat, relPath = "divmod/joint-conflict.smt2")
        vendored("truncating-xcsp3", Category.CSP, Expected.Sat,
            relPath = "divmod/truncating.xml", format = Format.XCSP3)
        workspace("truncating-minizinc", "klause-mzn-lib/test-models/klause-test-divmod.mzn", Category.OPTIMIZATION,
            Expected.Opt(-7), format = Format.MINIZINC)
    }
}
