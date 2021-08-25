package mfwgenerics.kotq.ddl.fluent

import mfwgenerics.kotq.ddl.built.BuildsIntoColumnDef
import mfwgenerics.kotq.ddl.built.BuiltColumnDef

interface ColumnIncrementable<T : Any>: ColumnNullable<T> {
    private class AutoIncrement<T : Any>(
        val lhs: ColumnIncrementable<T>
    ): ColumnNullable<T> {
        override fun buildIntoColumnDef(out: BuiltColumnDef): BuildsIntoColumnDef? {
            out.autoIncrement = true
            return lhs
        }
    }

    fun autoIncrement(): ColumnNullable<T> =
        AutoIncrement(this)
}