package com.eignex.klause.bench.catalog

internal object SourceOwnershipSuite {
    val suite = suite("source-ownership", "Bounded mixed arithmetic beside finite source constraints") {
        license = "Apache-2.0"
        for (name in listOf("mixed", "mixed-global")) {
            vendoredMzn(name, Category.CSP, Expected.Sat, model = "source-ownership/$name.mzn")
        }
    }
}
