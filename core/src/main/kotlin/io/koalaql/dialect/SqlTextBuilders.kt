package io.koalaql.dialect

import io.koalaql.Assignment
import io.koalaql.expr.*
import io.koalaql.expr.built.BuiltAggregatedExpr
import io.koalaql.query.*
import io.koalaql.query.built.*
import io.koalaql.sql.RawSqlBuilder
import io.koalaql.sql.SqlTextBuilder
import io.koalaql.values.RowIterator
import io.koalaql.values.ValuesRow
import io.koalaql.window.*

fun SqlTextBuilder.selectClause(selected: List<SelectedExpr<*>>, compileSelect: (SelectedExpr<*>) -> Unit) {
    addSql("SELECT ")

    if (selected.isNotEmpty()) {
        prefix("", "\n, ").forEach(selected) {
            compileSelect(it)
        }
    } else {
        addError("unable to generate empty select")
    }
}

fun SqlTextBuilder.compileRangeMarker(direction: String, marker: FrameRangeMarker<*>, compileExpr: (Expr<*>) -> Unit) {
    when (marker) {
        CurrentRow -> addSql("CURRENT ROW")
        is Following<*> -> compileExpr(marker.offset)
        is Preceding<*> -> compileExpr(marker.offset)
        Unbounded -> addSql("UNBOUNDED $direction")
    }
}

fun SqlTextBuilder.compileJoins(
    joins: Iterable<BuiltJoin>,
    compileRelation: (BuiltRelation) -> Unit,
    compileExpr: (Expr<*>) -> Unit
) {
    joins.forEach { join ->
        addSql("\n")
        addSql(join.type.sql)
        addSql(" ")
        compileRelation(join.to)
        addSql(" ON ")
        compileExpr(join.on)
    }
}

fun SqlTextBuilder.compileExpr(
    expr: QuasiExpr,
    emitParens: Boolean,
    impl: ExpressionCompiler
) {
    when (expr) {
        is AggregatedExpr<*> -> {
            val aggregated = BuiltAggregatedExpr.from(expr)

            addSql(aggregated.expr.type.sql)
            parenthesize {
                prefix("", ", ").forEach(aggregated.expr.args) {
                    impl.aggregatable(false, it)
                }
            }

            aggregated.filter?.let { filter ->
                addSql(" FILTER(WHERE ")

                compileExpr(filter, false, impl)

                addSql(")")
            }

            aggregated.over?.let { window ->
                addSql(" OVER (")
                impl.window(window)
                addSql(")")
            }
        }
        is CastExpr<*> -> {
            addSql("CAST")
            parenthesize {
                compileExpr(expr.of, false, impl)
                addSql(" AS ")
                impl.dataTypeForCast(expr.type)
            }
        }
        is ComparedQuery<*> -> {
            addSql(expr.type)
            impl.subquery(false, expr.subquery)
        }
        is ExprListExpr<*> -> {
            parenthesize {
                prefix("", ", ").forEach(expr.exprs) {
                    compileExpr(it, false, impl)
                }
            }
        }
        is Literal<*> -> addLiteral(expr)
        is OperationExpr<*> -> {
            when (expr.type.fixity) {
                OperationFixity.PREFIX -> parenthesize(emitParens) {
                    addSql(expr.type.sql)
                    addSql(" ")

                    compileExpr(expr.args.single(), false, impl)
                }
                OperationFixity.POSTFIX -> parenthesize(emitParens) {
                    compileExpr(expr.args.single(), false, impl)
                    addSql(" ")
                    addSql(expr.type.sql)
                }
                OperationFixity.INFIX -> parenthesize(emitParens) {
                    prefix("", " ${expr.type.sql} ").forEach(expr.args) {
                        compileExpr(it, true, impl)
                    }
                }
                OperationFixity.APPLY -> {
                    addSql(expr.type.sql)
                    parenthesize {
                        prefix("", ", ").forEach(expr.args) {
                            compileExpr(it, false, impl)
                        }
                    }
                }
            }
        }
        is AsReference<*> -> {
            val reference = expr.asReference()
            val excluded = reference.excludedReference()

            if (excluded != null) {
                impl.excluded(excluded)
            } else {
                impl.reference(false, reference)
            }
        }
        is SubqueryExpr<*> -> {
            impl.subquery(false, expr.buildQuery())
        }
        is BuiltCaseExpr<*> -> parenthesize(emitParens) {
            addSql("CASE ")

            expr.onExpr?.let { compileExpr(it, true, impl) }

            expr.whens.forEach { whenThen ->
                addSql("\nWHEN ")
                compileExpr(whenThen.whenExpr, false, impl)
                addSql(" THEN ")
                compileExpr(whenThen.thenExpr, true, impl)
            }

            expr.elseExpr?.let {
                addSql("\nELSE ")
                compileExpr(it, false, impl)
            }

            addSql("\nEND")
        }
        is RawExpr<*> -> {
            val build = expr.build

            object : RawSqlBuilder {
                override fun sql(value: String) { addSql(value) }
                override fun expr(expr: QuasiExpr) { compileExpr(expr, true, impl) }
            }.build()
        }
        else -> error("missed case $expr")
    }
}

fun SqlTextBuilder.compileOrderBy(ordinals: List<Ordinal<*>>, compileExpr: (Expr<*>) -> Unit) {
    prefix("ORDER BY ", ", ").forEach(ordinals) {
        val orderKey = it.toOrderKey()

        compileExpr(orderKey.expr)

        addSql(" ${orderKey.order.sql}")

        when (orderKey.nulls) {
            NullOrdering.FIRST -> addSql(" NULLS FIRST")
            NullOrdering.LAST -> addSql(" NULLS LAST")
            null -> { }
        }
    }
}

fun SqlTextBuilder.compileInsertLine(
    insert: BuiltInsert,
    table: TableRelation = insert.unwrapTable(),
    compileName: () -> Unit = { addIdentifier(table.tableName) }
) {
    val columns = insert.query.columns

    if (insert.ignore) {
        addSql("INSERT IGNORE INTO ")
    } else {
        addSql("INSERT INTO ")
    }

    val tableColumnMap = table.columns.associateBy { it }

    compileName()

    parenthesize {
        prefix("", ", ").forEach(columns) {
            val column = checkNotNull(tableColumnMap[it]) {
                "can't insert $it into ${table.tableName}"
            }

            addIdentifier(column.symbol)
        }
    }
}

fun SqlTextBuilder.compileQueryBody(
    body: BuiltQueryBody,
    compileExpr: (Expr<*>) -> Unit,
    compileRelation: (BuiltRelation) -> Unit,
    compileWindows: (windows: List<LabeledWindow>) -> Unit,
    compileJoins: (List<BuiltJoin>) -> Unit = { joins ->
        joins.asReversed().forEach { join ->
            addSql("\n")
            addSql(join.type.sql)
            addSql(" ")
            compileRelation(join.to)
            addSql(" ON ")
            compileExpr(join.on)
        }
    },
    compileWhere: (Expr<*>) -> Unit = { where ->
        addSql("\nWHERE ")
        compileExpr(where)
    },
    compileGroupBy: (List<Expr<*>>) -> Unit = {
        prefix("\nGROUP BY ", ", ").forEach(body.groupBy) {
            compileExpr(it)
        }
    },
    compileHaving: (Expr<*>) -> Unit = {
        addSql("\nHAVING ")
        compileExpr(it)
    }
) {
    compileRelation(body.relation)

    if (body.joins.isNotEmpty()) compileJoins(body.joins)
    body.where?.let(compileWhere)

    if (body.groupBy.isNotEmpty()) compileGroupBy(body.groupBy)
    body.having?.let(compileHaving)

    if (body.windows.isNotEmpty()) compileWindows(body.windows)
}

fun SqlTextBuilder.compileRow(
    columns: List<Reference<*>>,
    iter: RowIterator<ValuesRow>,
    compileExpr: (Expr<*>) -> Unit
) {
    addSql("(")
    prefix("", ", ").forEach(columns) {
        @Suppress("unchecked_cast")
        compileExpr(iter.row[it])
    }
    addSql(")")
}

fun SqlTextBuilder.compileValues(
    query: BuiltValuesQuery,
    compileExpr: (Expr<*>) -> Unit,
    compileRow: (List<Reference<*>>, RowIterator<ValuesRow>) -> Unit = { columns, it ->
        this.compileRow(columns, it, compileExpr)
    }
): Boolean {
    val values = query.values

    addSql("VALUES ")

    val iter = values.rowIterator()

    return if (iter.next()) {
        val rowPrefix = prefix("", "\n, ")

        do {
            rowPrefix.next {
                compileRow(values.columns, iter)
            }
        } while (iter.next())

        true
    } else {
        addError("couldn't generate empty values")

        false
    }
}

fun SqlTextBuilder.compileOnConflict(
    onConflict: OnConflictOrDuplicateAction?,
    compileAssignments: (List<Assignment<*>>) -> Unit
) {
    when (onConflict) {
        is OnConflictIgnore -> {
            addSql("\nON CONFLICT ON CONSTRAINT ")
            addIdentifier(onConflict.key.name)
            addSql(" DO NOTHING")
        }
        is OnConflictUpdate -> {
            addSql("\nON CONFLICT ON CONSTRAINT ")
            addIdentifier(onConflict.key.name)

            addSql(" DO UPDATE SET")

            check(onConflict.assignments.isNotEmpty()) {
                "empty assignment list in DO UPDATE SET"
            }

            compileAssignments(onConflict.assignments)
        }
        is OnDuplicateUpdate -> {
            addSql("\nON DUPLICATE KEY UPDATE")

            check(onConflict.assignments.isNotEmpty()) {
                "empty assignment list in ON DUPLICATE KEY UPDATE"
            }

            compileAssignments(onConflict.assignments)
        }
        null -> { }
    }
}