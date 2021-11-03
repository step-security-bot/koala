package io.koalaql.expr

import io.koalaql.query.BlockingPerformer
import io.koalaql.query.Queryable
import io.koalaql.values.RowSequence
import io.koalaql.values.RowWithThreeColumns
import io.koalaql.values.unsafeCastToThreeColumns

interface QueryableOfThree<A : Any, B : Any, C : Any>: Queryable<RowWithThreeColumns<A, B, C>> {
    override fun performWith(ds: BlockingPerformer): RowSequence<RowWithThreeColumns<A, B, C>> =
        ds.query(buildQuery()).unsafeCastToThreeColumns()
}