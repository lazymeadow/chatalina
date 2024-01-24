package com.applepeacock.database

import com.applepeacock.plugins.defaultMapper
import kotlinx.datetime.Clock
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.statements.api.PreparedStatementApi
import org.jetbrains.exposed.sql.statements.jdbc.JdbcConnectionImpl
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.vendors.currentDialect
import org.postgresql.util.PGobject


fun Table.systemTimestamp(name: String) = timestamp(name).clientDefault { Clock.System.now() }

sealed interface ChatTable {
    abstract class ObjectModel
    abstract class DAO {
        abstract fun resultRowToObject(row: ResultRow): ObjectModel
    }
}

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

// json column type (see: https://gist.github.com/qoomon/70bbbedc134fd2a149f1f2450667dc9d, https://gist.github.com/nvta-sbiyyala/fa327d449d98f37dacead7241af2c683)

val databaseMapper = defaultMapper

inline fun <reified T : Any> Table.jsonb(name: String): Column<T> =
    jsonb(name = name, klass = T::class.java)

fun <T : Any> Table.jsonb(name: String, klass: Class<T>): Column<T> =
    registerColumn(name = name, type = PostgresJsonBColumn(klass, false))

private class PostgresJsonBColumn<out T : Any>(
    private val klass: Class<T>,
    override var nullable: Boolean
) : IColumnType {
    override fun sqlType(): String = "JSONB"

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        val obj = PGobject()
        obj.type = sqlType()
        if (value != null)
            obj.value = value as String
        stmt[index] = obj
    }

    override fun valueFromDB(value: Any): Any = when (value) {
        is HashMap<*, *> -> value
        is Map<*, *> -> value
        else -> {
            if (value::class.java == klass) {
                value
            } else {
                value as PGobject
                try {
                    val json = value.value
                    databaseMapper.readValue(json, klass)
                } catch (e: Exception) {
                    e.printStackTrace()
                    throw RuntimeException("Can't parse JSON: $value")
                }
            }
        }
    }

    override fun nonNullValueToString(value: Any): String = "'${databaseMapper.writeValueAsString(value)}'::jsonb"

    override fun notNullValueToDB(value: Any): Any = databaseMapper.writeValueAsString(value)
}

// jsonb_set function
// jsonb_set(metadata, '{key}', '"new value"')
class JsonbSet<T : Any>(val column: Expression<T>, klass: Class<T>, val key: String, val value: Any?) :
    CustomFunction<T>(
        "JSONB_SET",
        PostgresJsonBColumn(klass, false)
    ) {
    override fun toQueryBuilder(queryBuilder: QueryBuilder): Unit = queryBuilder {
        append(functionName, '(')
        append(column)
        append(", ")
        append(stringLiteral("{${key}}"))
        append(", ")
        if (value is Expression<*>) {
            value.toQueryBuilder(this)
        } else {
            append(value?.let { "${stringParam("\"$it\"")}" } ?: stringLiteral("null"))
        }
        append(')')
    }
}

fun <T : Any> jsonbSet(col: Column<T>, klass: Class<T>, key: String, value: String?): JsonbSet<T> =
    JsonbSet(col, klass, key, value)


fun <T : Any> jsonbSetNullable(
    col: Expression<T?>,
    valueIfNull: T,
    klass: Class<T>,
    key: String,
    value: Any?
): JsonbSet<T> =
    JsonbSet<T>(Expression.build {
        case().When(col.isNotNull(), col).Else(jsonbParam<T>(valueIfNull, klass))
    }, klass, key, value)

fun <T : Any> jsonbParam(value: T, klass: Class<T>) =
    QueryParameter(value, PostgresJsonBColumn(klass, false))

class JsonBuildObject(vararg val pairs: Pair<String, Expression<*>>): CustomFunction<Map<*, *>>("JSON_BUILD_OBJECT", PostgresJsonBColumn(Map::class.java, false)) {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        append(functionName, "(")
        pairs.appendTo {(key, value) ->
            append(stringParam(key), ", ", value)
        }
        append(")")
    }
}

fun jsonBuildObject(vararg pairs: Pair<String, Expression<*>>) = JsonBuildObject(*pairs)

// json_agg function
fun <T : Any> jsonAgg(expr: QueryAlias, klass: Class<T>): ExpressionWithColumnType<T> = CustomFunction(
    "JSON_AGG",
    PostgresJsonBColumn(klass, false),
    wrapAsExpression<T>(expr.query).alias(expr.alias).aliasOnlyExpression()
)

// jsonb_agg function
class JsonbAgg<T : Any>(val expr1: Expression<*>, klass: Class<T>, val filter: Op<Boolean>? = null, val orderByCol: Expression<*>? = null, val orderByOrder: SortOrder = SortOrder.ASC) : CustomFunction<T>(
    "JSONB_AGG",
    PostgresJsonBColumn(klass, false)
) {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        append(functionName, "(", expr1)
        filter?.let {
            append(" FILTER (WHERE ", filter, ")")
        }
        orderByCol?.let {
            append(" ORDER BY ")
            currentDialect.dataTypeProvider.precessOrderByClause(this, orderByCol, orderByOrder)
        }
        append(")")
    }
}

fun <T : Any> jsonbAgg(expr: Expression<*>, klass: Class<T>, filter: Op<Boolean>? = null, orderByCol: Expression<*>? = null, orderByOrder: SortOrder = SortOrder.ASC) =
    JsonbAgg(expr, klass, filter, orderByCol, orderByOrder)

fun jsonbTextAgg(col: Column<String>): ExpressionWithColumnType<Array<String>> = CustomFunction(
    "JSONB_AGG",
    PostgresJsonBColumn(Array<String>::class.java, false),
    col
)

fun jsonbArrayElementsText(expr: ExpressionWithColumnType<*>): CustomFunction<String?> = CustomStringFunction(
    "JSONB_ARRAY_ELEMENTS_TEXT",
    expr
)

open class JsonArrowOp<T>(val expr1: Expression<*>, val expr2: Expression<*>) : ExpressionWithColumnType<Any?>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        append(expr1, "->", expr2)
    }

    infix fun eq(other: String): EqOp = EqOp(this, stringParam(other))
    override val columnType: IColumnType = TextColumnType()
}

class JsonDoubleArrowOp<T>(expr1: Expression<*>, expr2: Expression<*>) : JsonArrowOp<T>(expr1, expr2) {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        append(expr1, "->>", expr2)
    }
}

class JsonContainsArrowOp(expr1: Expression<*>, expr2: Expression<*>) : ComparisonOp(expr1, expr2, "@>")

// -> operator for json nodes by key
infix fun <T> Column<T>.singleArrow(string: String): JsonArrowOp<T> = JsonArrowOp(this, stringLiteral(string))

// ->> operator for json nodes as text
infix fun <T> Column<T>.doubleArrow(string: String): JsonDoubleArrowOp<T> =
    JsonDoubleArrowOp(this, stringLiteral(string))

infix fun <T> JsonArrowOp<T>.doubleArrow(string: String): JsonDoubleArrowOp<T> =
    JsonDoubleArrowOp(this, stringLiteral(string))

infix fun <T> JsonArrowOp<T>.containsArrow(expr: ExpressionAlias<Array<String>>): JsonContainsArrowOp =
    JsonContainsArrowOp(this, expr.aliasOnlyExpression())