package mfwgenerics.kotq.query.fluent

import mfwgenerics.kotq.dsl.and
import mfwgenerics.kotq.expr.Expr
import mfwgenerics.kotq.query.built.BuildsIntoSelectBody
import mfwgenerics.kotq.query.built.BuiltSelectBody

interface Havingable: Windowable {
    private class Having(
        val of: Havingable,
        val having: Expr<Boolean>
    ): Havingable {
        override fun buildIntoSelectBody(out: BuiltSelectBody): BuildsIntoSelectBody? {
            out.having = out.having?.and(having)?:having
            return of
        }
    }

    fun having(having: Expr<Boolean>): Havingable = Having(this, having)
}