package com.eignex.klause.bench.catalog

internal object QfNraPartialConflictSuite {
    val suite = suite("smtlib-qfnra-partial-conflicts-v1", "Partial numeric conflicts under Boolean guards") {
        format = Format.SMTLIB
        license = "Apache-2.0 (self-authored Eignex Klause fixtures)"
        for (layout in listOf("early", "late")) for (length in listOf(1, 2, 4)) {
            for ((outcome, expected) in listOf("sat" to Expected.Sat, "unsat" to Expected.Unsat)) {
                val name = "$layout-$length-$outcome"
                vendored(
                    name, expected = expected,
                    relPath = "qfnra-partial-conflicts-v1/$name.smt2",
                    tags = setOf("polynomial-real", "qfnra-partial-conflicts-v1"),
                )
            }
        }
    }
}
