package mfwgenerics.kotq.query

import mfwgenerics.kotq.expr.Reference

class LabelList(
    val values: List<Reference<*>>
) {
    private val positions = hashMapOf<Reference<*>, Int>()

    init {
        values.forEachIndexed { ix, it ->
            check (positions.putIfAbsent(it, ix) == null)
                { "duplicate label $it" }
        }
    }
}