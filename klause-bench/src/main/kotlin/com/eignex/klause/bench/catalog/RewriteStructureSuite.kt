package com.eignex.klause.bench.catalog

internal object RewriteStructureSuite {
    val suite = suite("rewrite-structure-v1", "Affine Table and Element representation controls") {
        format = Format.XCSP3
        license = "Apache-2.0"
        for ((name, optimum) in listOf("affine-table" to 0L, "element-cone" to 2L)) {
            vendored(name, Category.OPTIMIZATION, Expected.Opt(optimum),
                relPath = "rewrite-structure-v1/$name.xml", tags = setOf("presolve-structure"))
        }
    }
}
