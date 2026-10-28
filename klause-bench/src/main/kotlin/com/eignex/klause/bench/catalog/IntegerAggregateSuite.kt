package com.eignex.klause.bench.catalog

internal object IntegerAggregateSuite {
    val suite = suite("integer-aggregate-v1", "Thin primitive integer aggregate controls") {
        format = Format.SMTLIB
        license = "Apache-2.0 (self-authored Eignex Klause fixtures)"
        for (name in listOf("finite-gap", "open-gap", "gcd-gap")) {
            vendored(name, expected = Expected.Unsat, relPath = "integer-aggregate-v1/$name.smt2",
                tags = setOf("integer-aggregate"))
        }
    }
}
