package net.chatalina.jsonrpc

import io.ktor.util.reflect.*
import net.chatalina.chat.MessageTypes
import java.util.*


enum class ParameterType(private val niceName: String) {
    STRING("a string") {
        override fun validate(paramValue: Any): Boolean = validateClass<String>(paramValue)
    },
    INTEGER("an integer") {
        override fun validate(paramValue: Any): Boolean = validateClass<Int>(paramValue)
    },
    BOOLEAN("a boolean") {
        override fun validate(paramValue: Any): Boolean = validateClass<Boolean>(paramValue)
    },
    OBJECT("an object") {
        override fun validate(paramValue: Any): Boolean = validateClass<Map<*, *>>(paramValue)
    },
    INT_LIST("a list of integers") {
        override fun validate(paramValue: Any): Boolean {
            return (paramValue as? List<*>)?.let { specificList ->
                specificList.firstOrNull { it !is Int } == null
            } ?: false
        }
    },
    GUID("an uuid") {
        override fun validate(paramValue: Any): Boolean {
            return try {
                getValue(paramValue)
                true
            } catch (e: IllegalArgumentException) {
                false
            }
        }

        fun getValue(paramValue: Any): UUID = UUID.fromString(paramValue.toString())
    },
    DATE("a date string (YYYY-MM-DD)") {
        override fun validate(paramValue: Any): Boolean {
            // this regex says dates are only valid from 1900-01-01 to 2099-12-31 and that's fine.
            return validateRegex(paramValue, """(19|20)\d{2}-(0[1-9]|1[0-2])-([0-2][1-9]|3[0-1])""")
        }
    },
    EMAIL("a valid email address") {
        override fun validate(paramValue: Any): Boolean {
            return validateRegex(paramValue, """[A-Z0-9a-z._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,6}""")
        }
    };

    protected fun validateRegex(paramValue: Any, pattern: String): Boolean {
        return paramValue.toString().matches(Regex(pattern))
    }

    protected inline fun <reified T : Any> validateClass(paramValue: Any): Boolean {
        return (paramValue.instanceOf(T::class) || paramValue::class == T::class)
    }

    abstract fun validate(paramValue: Any): Boolean

    fun getErrorMessage(paramName: String) = "Param $paramName must be $niceName"

    override fun toString(): String {
        return niceName
    }
}

data class Parameter(val name: String, val type: ParameterType)
