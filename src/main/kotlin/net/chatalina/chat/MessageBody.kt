package net.chatalina.chat

import net.chatalina.plugins.defaultMapper
import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.module.kotlin.convertValue
import java.util.*
import kotlin.enums.enumEntries
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

class OtherMapDelegate<B : MessageBody, T>(val key: String, val getValueFromOther: (B, KProperty<*>) -> T?) :
    ReadOnlyProperty<B, T?> {
    override fun getValue(thisRef: B, property: KProperty<*>): T? {
        return getValueFromOther(thisRef, property)
    }
}

open class MessageBody(
    val type: MessageTypes,
    @JsonAnySetter
    @JsonAnyGetter
    val other: MutableMap<String, Any?> = mutableMapOf()
) {
    @OptIn(ExperimentalStdlibApi::class)
    inline fun <reified T : Enum<T>, B : MessageBody> otherEnum(key: String) = object : ReadOnlyProperty<B, T?> {
        override fun getValue(thisRef: B, property: KProperty<*>): T? {
            return enumEntries<T>().find { it.toString() == thisRef.other[key].toString() }
        }
    }

    inline fun <reified T : Any, B : MessageBody> other(key: String) =
        OtherMapDelegate<B, T>(key) { thisRef, property ->
            thisRef.other[key]?.let {
                if (T::class == UUID::class) {
                    try {
                        UUID.fromString(it.toString()) as T
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                } else {
                    try {
                        it as T
                    } catch (e: ClassCastException) {
                        defaultMapper.convertValue(it)
                    }
                }
            }
        }
}
