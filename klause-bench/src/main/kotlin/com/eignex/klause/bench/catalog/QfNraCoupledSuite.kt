package com.eignex.klause.bench.catalog

internal object QfNraCoupledSuite {
    val suite = suite("smtlib-qfnra-coupled-v1", "Coupled exact sections and connected conjunctions") {
        format = Format.SMTLIB
        license = "Apache-2.0 (self-authored Eignex Klause fixtures)"
        for ((name, expected) in listOf(
            "cycle-unsat" to Expected.Unsat,
            "cycle-sat" to Expected.Sat,
            "cycle-alternative" to Expected.Sat,
            "shifted-unsat" to Expected.Unsat,
            "shifted-sat" to Expected.Sat,
            "shifted-alternative" to Expected.Sat,
            "repeated-unsat" to Expected.Unsat,
            "repeated-sat" to Expected.Sat,
            "repeated-alternative" to Expected.Sat,
            "strict-cycle-unsat" to Expected.Unsat,
            "weak-cycle-sat" to Expected.Sat,
            "uncovered-singular-sat" to Expected.Sat,
        )) {
            vendored(name, expected = expected, relPath = "qfnra-coupled-v1/$name.smt2",
                tags = setOf("polynomial-real", "qfnra-coupled-v1"))
        }
    }
}
