package com.eignex.klause.bench.catalog

internal object NonlinearIntegerBooleanResidueSuite {
    val suite = suite("smtlib-nonlinear-integer-boolean-residues-v1", "Boolean integer residue consumption controls") {
        format = Format.SMTLIB
        license = "Apache-2.0 (self-authored Eignex Klause fixtures)"
        for ((name, expected) in listOf(
            "covered-unsat" to Expected.Unsat,
            "negated-support-sat" to Expected.Sat,
        )) {
            vendored(name, expected = expected, relPath = "nonlinear-integer-boolean-residues-v1/$name.smt2",
                tags = setOf("nonlinear-integer", "nonlinear-integer-boolean-residues-v1"))
        }
    }
}
