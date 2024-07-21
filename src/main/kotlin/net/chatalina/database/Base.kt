package net.chatalina.database

import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.datetime.Instant
import net.chatalina.plugins.defaultMapper
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.json.Extract
import org.jetbrains.exposed.sql.json.JsonColumnType
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.CustomTimeStampFunction
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp


fun Table.systemTimestamp(name: String) = timestamp(name).defaultExpression(CurrentTimestamp)
inline fun <reified T : Any> Table.jsonb(name: String) =
    this.jsonb(name, { defaultMapper.writeValueAsString(it) }, { defaultMapper.readValue<T>(it) })

inline fun <reified T : Any> ExpressionWithColumnType<*>.extract(
    vararg path: String,
    toScalar: Boolean = true
): Extract<T> {
    @OptIn(InternalApi::class)
    val columnType = resolveColumnType(
        T::class,
        defaultType = JsonColumnType(
            { defaultMapper.writeValueAsString(it) },
            { defaultMapper.readValue<T>(it) }
        )
    )
    return Extract(this, path = path, toScalar, this.columnType, columnType)
}

sealed interface ChatTable {
    abstract class ObjectModel
    abstract class DAO {
        abstract fun resultRowToObject(row: ResultRow): ObjectModel
    }
}

fun <T : Comparable<T>> Column<EntityID<T>>.asString() = this.castTo<String>(VarCharColumnType())

// timestamp "greatest" aggregation
fun Column<Instant>.greatest(vararg timestamps: Expression<Instant?>) =
    CustomTimeStampFunction("GREATEST", this, *timestamps)

class ArrayAggFunction<T : Any?>(expr: Expression<*>, _columnType: IColumnType<List<T>>) :
    CustomFunction<List<T>>("array_agg", _columnType, expr)

fun Expression<EntityID<String>>.arrayAgg(): ArrayAggFunction<String> =
    ArrayAggFunction(this, ArrayColumnType(TextColumnType()))
