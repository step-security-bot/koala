package io.koalaql.test.service

import io.koalaql.dsl.*
import io.koalaql.jdbc.JdbcDatabase
import io.koalaql.jdbc.performWith
import io.koalaql.setTo
import io.koalaql.test.models.*
import io.koalaql.test.table.ReviewTable
import io.koalaql.test.table.UserTable
import io.koalaql.test.table.UserVenueTable
import io.koalaql.test.table.VenueTable

class VenueService(
    private val db: JdbcDatabase
) {
    init {
        db.createTables(UserTable, VenueTable, UserVenueTable, ReviewTable)
    }

    fun createVenues(venues: List<NewVenue>): List<Int> = db.transact { cxn ->
        VenueTable
            .insert(values(venues) {
                set(VenueTable.name, it.name)
                set(VenueTable.description, it.description)
                set(VenueTable.closedPermanently, it.closed)
                set(VenueTable.type, it.type)
            })
            .returning(VenueTable.id)
            .performWith(cxn)
            .map { it.getOrNull(VenueTable.id)!! }
            .toList()
    }

    fun fetchVenues(ids: List<Int>? = null): List<Venue> {
        return db.transact { cxn ->
            val visits = name<Int>()
            val visitCte = cte()

            val rows = VenueTable
                .with(visitCte as_ UserVenueTable
                    .where(UserVenueTable.visited)
                    .groupBy(UserVenueTable.venue)
                    .select(UserVenueTable.venue, count() as_ visits)
                )
                .leftJoin(visitCte, UserVenueTable.venue eq VenueTable.id)
                .let {
                    if (ids != null) it.where(VenueTable.id inValues ids)
                    else it
                }
                .orderBy(VenueTable.id)
                .select(VenueTable, coalesce(visits, value(0)) as_ visits)
                .performWith(cxn)
                .toList()

            val reviewsByVenue = ReviewTable
                .where(ReviewTable.venue inValues rows.map { it[VenueTable.id] })
                .orderBy(ReviewTable.venue, ReviewTable.created.desc(), ReviewTable.user)
                .selectAll()
                .performWith(cxn)
                .groupBy({ it[ReviewTable.venue] }) { row ->
                    VenueReview(
                        user = row[ReviewTable.user],
                        created = row[ReviewTable.created],
                        edited = row[ReviewTable.edited],
                        content = row[ReviewTable.contents]
                    )
                }

            rows.map { row ->
                Venue(
                    id = row[VenueTable.id],
                    created = row[VenueTable.created],
                    name = row[VenueTable.name],
                    description = row[VenueTable.description],
                    closed = row[VenueTable.closedPermanently],
                    rating = row[VenueTable.rating],
                    type = row[VenueTable.type],
                    visits = row[visits],
                    reviews = reviewsByVenue[row[VenueTable.id]].orEmpty()
                )
            }
            .toList()
        }
    }

    fun mergeReviews(reviews: List<NewReview>) {
        db.transact { cxn ->
            ReviewTable
                .insert(values(reviews.map {
                    rowOf(
                        ReviewTable.user setTo it.user,
                        ReviewTable.venue setTo it.venue,
                        ReviewTable.contents setTo it.content
                    )
                }))
                .performWith(cxn)
        }
    }

    fun deleteReviews(reviews: List<ReviewKey>) {
        db.transact { cxn ->
            val deleted = alias() as_ values(reviews) {
                set(ReviewTable.user, it.user)
                set(ReviewTable.venue, it.venue)
            }

            ReviewTable
                .with(deleted)
                .where(exists(deleted
                    .where(deleted[ReviewTable.venue] eq ReviewTable.venue)
                    .where(deleted[ReviewTable.user] eq ReviewTable.user)
                    .selectJust(deleted[ReviewTable.venue])
                ))
                .delete()
                .performWith(cxn)
        }
    }

    fun updateVisits(
        updates: List<VenueVisitorUpdate>
    ) {
        db.transact { cxn ->
            UserVenueTable
                .insert(values(updates) {
                    set(UserVenueTable.venue, it.venue)
                    set(UserVenueTable.user, it.user)
                    set(UserVenueTable.visited, it.state)
                })
                .onDuplicate()
                .set(UserVenueTable.visited)
                .performWith(cxn)
        }
    }
}