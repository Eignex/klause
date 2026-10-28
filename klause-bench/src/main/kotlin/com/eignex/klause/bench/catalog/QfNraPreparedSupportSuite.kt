package com.eignex.klause.bench.catalog

internal object QfNraPreparedSupportSuite {
    val suite = suite("smtlib-qfnra-prepared-support-v1", "Frozen prepared coupled-support branches") {
        format = Format.SMTLIB
        license = "Apache-2.0 (self-authored Eignex Klause fixtures)"
        for (name in listOf("degree-2-pairs-1-sat", "degree-8-pairs-1-sat", "strict-alternative")) {
            vendored(name, expected = Expected.Sat, relPath = "qfnra-prepared-support-v1/$name.smt2",
                tags = setOf("polynomial-real", "qfnra-prepared-support-v1"))
        }
    }
}
