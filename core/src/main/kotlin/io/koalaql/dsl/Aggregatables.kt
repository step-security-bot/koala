package io.koalaql.dsl

import io.koalaql.expr.*
import io.koalaql.expr.fluent.FilterableExpr

fun <T : Any> distinct(expr: Expr<T>): OrderableAggregatable<T> =
    DistinctAggregatable(expr)

fun <T : Any> max(aggregatable: Aggregatable<T>): FilterableExpr<T> =
    GroupedOperationExpr(GroupedOperationType.MAX, listOf(aggregatable.buildAggregatable()))

fun <T : Any> sum(aggregatable: Aggregatable<T>): FilterableExpr<T> =
    GroupedOperationExpr(GroupedOperationType.SUM, listOf(aggregatable.buildAggregatable()))

fun count(aggregatable: Aggregatable<*>): FilterableExpr<Int> =
    GroupedOperationExpr(GroupedOperationType.COUNT, listOf(aggregatable.buildAggregatable()))

fun count(): FilterableExpr<Int> = count(value(1))