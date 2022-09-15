package net.chatalina.database

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.jdbc.JdbcConnectionImpl
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.util.*

// from https://github.com/LorittaBot/Loritta

open class ArrayColumnType<T>(private val type: ColumnType) : ColumnType() {
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

class TextArrayColumnType() : ArrayColumnType<String>(TextColumnType())

fun Table.textArray(name: String): Column<Array<String>> = registerColumn(name, TextArrayColumnType())

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

class ContainsOp(expr1: Expression<*>, expr2: Expression<*>) : ComparisonOp(expr1, expr2, "@>")
class OverlapsOp(expr1: Expression<*>, expr2: Expression<*>) : ComparisonOp(expr1, expr2, "&&")

infix fun <T, S> ExpressionWithColumnType<T>.any(t: S): Op<Boolean> {
    if (t == null) {
        return IsNullOp(this)
    }
    return AnyOp(this, QueryParameter(t, columnType))
}

infix fun <T, S> Expression<T>.any(expr: Expression<S>): Op<Boolean> {
    return AnyOp(this, expr)
}

infix fun <T, S> ExpressionWithColumnType<T>.contains(array: Array<in S>): Op<Boolean> =
    ContainsOp(this, QueryParameter(array, columnType))

infix fun <T, S> ExpressionWithColumnType<T>.overlaps(array: Array<in S>): Op<Boolean> =
    OverlapsOp(this, QueryParameter(array, columnType))

class ArrayAggFunction<T : Any?>(expr: Expression<*>, _columnType: IColumnType) :
    CustomFunction<Array<T>>("array_agg", _columnType, expr)

fun Expression<Int>.intArrayAgg(): ArrayAggFunction<Int> =
    ArrayAggFunction(this, ArrayColumnType<Int>(IntegerColumnType()))

fun Expression<UUID>.uuidArrayAgg(): ArrayAggFunction<UUID> =
    ArrayAggFunction<UUID>(this, UUIDColumnType())
