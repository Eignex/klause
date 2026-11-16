package com.eignex.klause.bench.catalog

internal object SourceEqualitySuite {
    val suite: Suite = suite("source-equalities-v1", "Exact real, mixed and wide equality blocks") {
        format = Format.SMTLIB
        license = "Apache-2.0 (self-authored Eignex Klause fixtures)"
        for (name in listOf("real-dependent", "fractional-dependent", "mixed-dependent", "wide-dependent",
            "polynomial-dependent", "guarded-alternative", "real-inconsistent")) {
            vendored(name, expected = if (name == "real-inconsistent") Expected.Unsat else Expected.Sat,
                relPath = "source-equalities-v1/$name.smt2", tags = setOf("source-equalities"))
        }
    }
}
