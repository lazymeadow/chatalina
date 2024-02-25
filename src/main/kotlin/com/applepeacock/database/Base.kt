package com.applepeacock.database

import kotlinx.datetime.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.CustomTimeStampFunction
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.statements.jdbc.JdbcConnectionImpl
import org.jetbrains.exposed.sql.transactions.TransactionManager


fun Table.systemTimestamp(name: String) = timestamp(name).defaultExpression(CurrentTimestamp())

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T : Any> Table.jsonb(name: String) = this.jsonb<T>(name, Json {
    decodeEnumsCaseInsensitive = true
    encodeDefaults = true
})

sealed interface ChatTable {
    abstract class ObjectModel
    abstract class DAO {
        abstract fun resultRowToObject(row: ResultRow): ObjectModel
    }
}

fun <T: Comparable<T>> Column<EntityID<T>>.asString() = this.castTo<String>(VarCharColumnType())

// timestamp "greatest" aggregation
fun Column<Instant>.greatest(vararg timestamps: Expression<Instant?>) =
    CustomTimeStampFunction("GREATEST", this, *timestamps)

// arrays, i guess
open class ArrayColumnType(private val type: ColumnType) : ColumnType() {
    override fun sqlType(): String = "${type.sqlType()} ARRAY"

    override fun valueToDB(value: Any?): Any? {
        if (value is Array<*>) {
            val columnType = type.sqlType().split("(")[0]
            val jdbcConnection = (TransactionManager.current().connection as JdbcConnectionImpl).connection
            return jdbcConnection.createArrayOf(columnType, value)
        } else {
            return super.valueToDB(value)
        }
    }

    override fun valueFromDB(value: Any): Any {
        if (value is java.sql.Array) {
            return value.array
        }
        if (value is Array<*>) {
            return value
        }
        error("Unsupported array")
    }

    override fun notNullValueToDB(value: Any): Any {
        if (value is Array<*>) {
            if (value.isEmpty())
                return "'{}'"

            val columnType = type.sqlType().split("(")[0]
            val jdbcConnection = (TransactionManager.current().connection as JdbcConnectionImpl).connection
            return jdbcConnection.createArrayOf(columnType, value) ?: error("Can't create non null array for $value")
        } else {
            return super.notNullValueToDB(value)
        }
    }
}

class ArrayAggFunction<T : Any?>(expr: Expression<*>, _columnType: IColumnType) :
    CustomFunction<Array<T>>("array_agg", _columnType, expr)

fun Expression<EntityID<String>>.arrayAgg(): ArrayAggFunction<String> =
    ArrayAggFunction(this, ArrayColumnType(TextColumnType()))


class AnyOp(val expr1: Expression<*>, val expr2: Expression<*>) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        if (expr2 is OrOp) {
            queryBuilder.append("(").append(expr2).append(")")
        } else {
            queryBuilder.append(expr2)
        }
        queryBuilder.append(" = ANY (")
        if (expr1 is OrOp) {
            queryBuilder.append("(").append(expr1).append(")")
        } else {
            queryBuilder.append(expr1)
        }
        queryBuilder.append(")")
    }
}

infix fun <T, S> ExpressionWithColumnType<T>.any(t: S): Op<Boolean> {
    if (t == null) {
        return IsNullOp(this)
    }
    return AnyOp(this, QueryParameter(t, columnType))
}

infix fun <T, S> Expression<T>.any(expr: Expression<S>): Op<Boolean> {
    return AnyOp(this, expr)
}
