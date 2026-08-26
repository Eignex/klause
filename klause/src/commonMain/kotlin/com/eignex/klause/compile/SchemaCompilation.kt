package com.eignex.klause.compile

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.model.SchemaEntry
import com.eignex.skema.SchemaDef

/** Compiles a decoded schema definition into immutable solver structures. */
fun compileSchema(
    definition: SchemaDef<SchemaEntry>,
    config: KlauseConfig = KlauseConfig.current,
): CompiledSchema = Compiler(config).compile(definition)
