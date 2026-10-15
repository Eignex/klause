package com.eignex.klause.bench.catalog

internal object AbsSuite {
    val suite = suite("abs-relations", "Integer and real absolute value source relations") {
        license = "internal"
        for (name in listOf("magnitude", "aliases", "scopes", "optional", "real", "real-alias")) {
            vendoredMzn(name, Category.CSP, Expected.Sat, model = "abs-relations/$name.mzn")
        }
    }
}
