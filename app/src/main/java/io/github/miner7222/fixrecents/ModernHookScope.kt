package io.github.miner7222.fixrecents

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

internal class ModernHookScope(
    private val module: XposedModule,
    val classLoader: ClassLoader,
) {
    fun interceptMethod(
        className: String,
        methodName: String,
        vararg parameterTypes: Class<*>,
        block: (XposedInterface.Chain) -> Any?,
    ) {
        val executable = resolveExecutable(className, methodName, parameterTypes)
        module.hook(executable)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept(block)
    }

    fun loadClass(className: String): Class<*> {
        return Class.forName(className, false, classLoader)
    }

    private fun resolveExecutable(
        className: String,
        methodName: String,
        parameterTypes: Array<out Class<*>>,
    ): Executable {
        val targetClass = loadClass(className)
        val executable = targetClass.declaredMethods.firstOrNull {
            it.name == methodName && it.parameterTypes.contentEquals(parameterTypes)
        } ?: throw NoSuchMethodException("${targetClass.name}#$methodName${describeParameters(parameterTypes)}")
        executable.isAccessible = true
        return executable
    }

    private fun describeParameters(parameterTypes: Array<out Class<*>>): String {
        return parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
    }
}
