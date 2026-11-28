package com.eignex.klause.bench.profile

import java.lang.reflect.Method

internal class CliPreparationReflection {
    private val prefix = "com.eignex.klause.cli."
    val commonType: Class<*> = type("CommonOptions")
    val common: Any = commonType.getConstructor().newInstance()
    private val coreType = type("SolveCore")
    val core: Any = coreType.getField("INSTANCE").get(null)

    fun type(name: String): Class<*> = Class.forName(prefix + name)

    fun method(owner: Class<*>, name: String, vararg parameters: Class<*>): Method =
        owner.getDeclaredMethod(name, *parameters).also { it.isAccessible = true }

    fun top(owner: String, name: String, vararg parameters: Class<*>): Method = method(type(owner), name, *parameters)

    fun core(name: String, vararg parameters: Class<*>): Method = method(coreType, name, *parameters)

    fun get(owner: Any, name: String): Any? = method(owner.javaClass, "get$name").invoke(owner)

    fun set(name: String, parameter: Class<*>, value: Any?) {
        method(commonType, "set$name", parameter).invoke(common, value)
    }

    fun internalGet(owner: Any, name: String): Any? =
        method(owner.javaClass, "get$name\$klause_klause_cli").invoke(owner)
}
