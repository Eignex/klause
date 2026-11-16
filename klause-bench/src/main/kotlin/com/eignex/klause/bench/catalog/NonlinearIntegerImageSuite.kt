package com.eignex.klause.bench.catalog

internal object NonlinearIntegerImageSuite {
    val suite = suite("smtlib-nonlinear-integer-images-v1", "Compositional integer residue image controls") {
        format = Format.SMTLIB
        license = "Apache-2.0 (self-authored Eignex Klause fixtures)"
        for ((name, expected) in listOf(
            "powers-seven" to Expected.Unsat,
            "powers-three" to Expected.Sat,
            "powers-four" to Expected.Sat,
        )) {
            vendored(name, expected = expected, relPath = "nonlinear-integer-images-v1/$name.smt2",
                tags = setOf("nonlinear-integer", "nonlinear-integer-images-v1"))
        }
    }
}
