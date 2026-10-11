package com.eignex.klause.bench.catalog

internal object QfNraCanonicalSuite {
    val suite = suite("smtlib-qfnra-source-canonical-v1", "Canonical negative constants in polynomial-real sources") {
        format = Format.SMTLIB
        license = "Apache-2.0 (self-authored Eignex Klause fixtures)"
        for (name in listOf("degeneracy/degree-drop", "degeneracy/equal-degree-common-root")) {
            vendored(
                name, expected = Expected.Sat,
                relPath = "qfnra-source-canonical-v1/$name.smt2",
                tags = setOf("polynomial-real", "qfnra-source-canonical-v1"),
            )
        }
    }
}
