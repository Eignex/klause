package com.eignex.klause.bench.catalog

internal object QuadraticProposalSuite {
    val suite = suite("smtlib-quadratic-proposals-v1", "Quadratic active-row proposal source controls") {
        format = Format.SMTLIB
        license = "Apache-2.0 (self-authored Eignex Klause fixtures)"
        for (name in listOf(
            "interior-1",
            "coupled-interior-2",
            "active-boundary-2",
            "integer-interior-2",
            "diagonal-16-rows-128",
            "diagonal-32-rows-256",
            "coupled-32-rows-256",
            "diagonal-64-rows-512",
        )) {
            vendored(name, Category.OPTIMIZATION, Expected.Opt(0),
                relPath = "quadratic-proposals-v1/$name.smt2",
                tags = setOf("quadratic-objective", "quadratic-proposals-v1"))
        }
    }
}
