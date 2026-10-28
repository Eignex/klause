package com.eignex.klause.bench.catalog

internal object NonlinearIntegerResidueSuite {
    val suite = suite("smtlib-nonlinear-integer-residues-v1", "Mandatory multivariate integer residue controls") {
        format = Format.SMTLIB
        license = "Apache-2.0 (self-authored Eignex Klause fixtures)"
        for ((name, expected) in listOf(
            "squares-unsat" to Expected.Unsat,
            "unbounded-unsat" to Expected.Unsat,
            "shared-unsat" to Expected.Unsat,
            "rational-unsat" to Expected.Unsat,
            "wide-unsat" to Expected.Unsat,
            "squares-sat" to Expected.Sat,
            "zero-divisors-sat" to Expected.Sat,
            "guarded-sat" to Expected.Sat,
            "support-inconclusive" to Expected.Unsat,
            "real-sat" to Expected.Sat,
        )) {
            vendored(name, expected = expected, relPath = "nonlinear-integer-residues-v1/$name.smt2",
                tags = setOf("nonlinear-integer", "nonlinear-integer-residues-v1"))
        }
    }
}
