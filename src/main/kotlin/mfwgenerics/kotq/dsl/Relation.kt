package mfwgenerics.kotq.dsl

import mfwgenerics.kotq.expr.Labeled
import mfwgenerics.kotq.expr.RelvarColumn
import mfwgenerics.kotq.query.Alias
import mfwgenerics.kotq.query.Aliased
import mfwgenerics.kotq.query.AliasedRelation
import mfwgenerics.kotq.query.built.BuiltRelation
import mfwgenerics.kotq.query.built.BuiltSubquery

sealed interface Relation: AliasedRelation {
    fun alias(alias: Alias): AliasedRelation = Aliased(this, alias)

    override fun buildQueryRelation(): BuiltRelation
        = BuiltRelation(this, null)
}

interface Relvar: Relation {
    val relvarName: String

    val columns: List<RelvarColumn<*>>
}

class Subquery(
    val of: BuiltSubquery
): Relation {
    override fun namedExprs(): List<Labeled<*>> {
        error("not implemented")
    }
}