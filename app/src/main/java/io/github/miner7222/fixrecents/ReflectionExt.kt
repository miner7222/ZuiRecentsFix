package io.github.miner7222.fixrecents

import java.lang.reflect.Field
import java.lang.reflect.Method

internal fun Any.fieldValue(name: String): Any? {
    val field = findField(javaClass, name) ?: return null
    return try {
        field.get(this)
    } catch (ignored: Exception) {
        null
    }
}

internal fun Any.setFieldValue(name: String, value: Any?) {
    val field = findField(javaClass, name) ?: return
    runCatching { field.set(this, value) }
}

internal fun Any.booleanField(name: String): Boolean {
    return fieldValue(name) as? Boolean ?: false
}

internal fun Any.intField(name: String): Int {
    return fieldValue(name) as? Int ?: 0
}

internal fun Any.stringField(name: String): String? {
    return fieldValue(name) as? String
}

internal fun Any.callMethod(name: String, parameterTypes: Array<Class<*>>, args: Array<Any?>): Any? {
    val method = findMethod(javaClass, name, parameterTypes) ?: return null
    return try {
        method.invoke(this, *args)
    } catch (ignored: Exception) {
        null
    }
}

internal fun findFieldAssignableTo(type: Class<*>, fieldType: Class<*>): Field? {
    var current: Class<*>? = type
    while (current != null) {
        for (field in current.declaredFields) {
            if (fieldType.isAssignableFrom(field.type)) {
                field.isAccessible = true
                return field
            }
        }
        current = current.superclass
    }
    return null
}

private fun findField(type: Class<*>, name: String): Field? {
    var current: Class<*>? = type
    while (current != null) {
        try {
            val field = current.getDeclaredField(name)
            field.isAccessible = true
            return field
        } catch (ignored: NoSuchFieldException) {
            current = current.superclass
        }
    }
    return null
}

private fun findMethod(type: Class<*>, name: String, parameterTypes: Array<Class<*>>): Method? {
    var current: Class<*>? = type
    while (current != null) {
        val method = current.declaredMethods.firstOrNull {
            it.name == name && it.parameterTypes.contentEquals(parameterTypes)
        }
        if (method != null) {
            method.isAccessible = true
            return method
        }
        current = current.superclass
    }
    return null
}
