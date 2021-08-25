import mfwgenerics.kotq.ddl.DataType
import mfwgenerics.kotq.ddl.Table
import mfwgenerics.kotq.ddl.createTables
import mfwgenerics.kotq.dialect.h2.H2Dialect
import mfwgenerics.kotq.dsl.*
import mfwgenerics.kotq.expr.`as`
import mfwgenerics.kotq.jdbc.ConnectionWithDialect
import mfwgenerics.kotq.jdbc.TableDiffer
import mfwgenerics.kotq.jdbc.performWith
import mfwgenerics.kotq.setTo
import java.sql.DriverManager
import kotlin.test.BeforeTest
import kotlin.test.Test

class TestH2 {
    @Test
    fun `triangular numbers from values clause subquery`() {
        val cxn = ConnectionWithDialect(
            H2Dialect(),
            DriverManager.getConnection("jdbc:h2:mem:")
        )

        val number = name<Int>("number")
        val summed = name<Int>("sumUnder")

        /* need this cast to workaround H2 bug (? in VALUES aren't typed correctly) */
        val castNumber = cast(number, DataType.INT32)

        val alias = alias("A")

        val results = values((1..20).asSequence(), number)
            { value(number, it) }
            .subquery()
            .orderBy(castNumber.desc())
            .select(
                number,
                sum(castNumber)
                    .over(all()
                    .orderBy(castNumber)
                ) `as` summed
            )
            .subquery()
            .alias(alias)
            .where(alias[summed] greater 9)
            .select(alias[number], alias[summed])
            .performWith(cxn)
            .map { row ->
                "${row[alias[number]]}, ${row[alias[summed]]}"
            }
            .joinToString("\n")

        val expected = (1..20)
            .scan(0) { x, y -> x + y }
            .filter { it > 9 }
            .withIndex()
            .reversed()
            .asSequence()
            .map {
                "${it.index + 4}, ${it.value}"
            }
            .joinToString("\n")

        assert(expected == results)

        cxn.jdbc.close()
    }

    object ShopTable: Table("Shop") {
        val id = column("id", DataType.INT32.autoIncrement())

        val name = column("name", DataType.VARCHAR(100))
    }

    object CustomerTable: Table("Customer") {
        val id = column("id", DataType.INT32.autoIncrement())

        val firstName = column("firstName", DataType.VARCHAR(100))
        val lastName = column("lastName", DataType.VARCHAR(100))
    }

    object PurchaseTable: Table("Purchase") {
        val id = column("id", DataType.INT32.autoIncrement())

        val shop = column("shop", DataType.INT32.reference(ShopTable.id))
        val customer = column("customer", DataType.INT32.reference(CustomerTable.id))

        val product = column("product", DataType.VARCHAR(200))

        val price = column("price", DataType.INT32)
        val discount = column("discount", DataType.INT32.nullable())
    }

    // TODO use an assertion library
    private fun assertListEquals(expected: List<Any?>, actual: List<Any?>) {
        assert(expected.size == actual.size)

        repeat(expected.size) {
            assert(expected[it] == actual[it])
        }
    }

    private fun assertListOfListsEquals(expected: List<List<Any?>>, actual: List<List<Any?>>) {
        assert(expected.size == actual.size)

        repeat(expected.size) {
            assertListEquals(expected[it], actual[it])
        }
    }

    fun createAndPopulate(cxn: ConnectionWithDialect) {
        cxn.ddl(createTables(
            ShopTable,
            CustomerTable,
            PurchaseTable
        ))

        val shopIds = ShopTable
            .insert(values(
                rowOf(ShopTable.name setTo "Hardware"),
                rowOf(ShopTable.name setTo "Groceries"),
                rowOf(ShopTable.name setTo "Stationary")
            ))
            .returning(ShopTable.id)
            .performWith(cxn)
            .map { it[ShopTable.id]!! }
            .toList()

        val hardwareId = shopIds[0]
        val groceriesId = shopIds[1]
        val stationaryId = shopIds[2]

        val customerIds = CustomerTable
            .insert(values(
                rowOf(
                    CustomerTable.firstName setTo "Jane",
                    CustomerTable.lastName setTo "Doe"
                ),
                rowOf(
                    CustomerTable.firstName setTo "Bob",
                    CustomerTable.lastName setTo "Smith"
                )
            ))
            .returning(CustomerTable.id)
            .performWith(cxn)
            .map { it[CustomerTable.id]!! }
            .toList()

        val janeId = customerIds[0]
        val bobId = customerIds[1]

        PurchaseTable
            .insert(values(
                rowOf(
                    PurchaseTable.shop setTo groceriesId,
                    PurchaseTable.customer setTo janeId,
                    PurchaseTable.product setTo "Apple",
                    PurchaseTable.price setTo 150,
                    PurchaseTable.discount setTo 20
                ),
                rowOf(
                    PurchaseTable.shop setTo groceriesId,
                    PurchaseTable.customer setTo bobId,
                    PurchaseTable.product setTo "Pear",
                    PurchaseTable.price setTo 200
                ),
                rowOf(
                    PurchaseTable.shop setTo hardwareId,
                    PurchaseTable.customer setTo janeId,
                    PurchaseTable.product setTo "Hammer",
                    PurchaseTable.price setTo 8000
                ),
                rowOf(
                    PurchaseTable.shop setTo stationaryId,
                    PurchaseTable.customer setTo bobId,
                    PurchaseTable.product setTo "Pen",
                    PurchaseTable.price setTo 500
                ),
            ))
            .performWith(cxn)
    }

    @Test
    fun `stringy joins`() {
        val cxn = ConnectionWithDialect(
            H2Dialect(),
            DriverManager.getConnection("jdbc:h2:mem:")
        )

        createAndPopulate(cxn)

        val expectedPurchaseItems = listOf(
            listOf("Bob", "Pear", 200),
            listOf("Bob", "Pen", 500),
            listOf("Jane", "Apple", 150),
            listOf("Jane", "Hammer", 8000)
        )

        val actualPurchaseItems = CustomerTable
            .innerJoin(PurchaseTable, CustomerTable.id eq PurchaseTable.customer)
            .orderBy(CustomerTable.firstName)
            .select(CustomerTable.firstName, PurchaseTable.product, PurchaseTable.price)
            .performWith(cxn)
            .map { row -> row.labels.values.map { row[it] } }
            .toList()

        assertListOfListsEquals(expectedPurchaseItems, actualPurchaseItems)

        val total = name<Int>()

        val actualTotals = CustomerTable
            .innerJoin(PurchaseTable, CustomerTable.id eq PurchaseTable.customer)
            .groupBy(CustomerTable.id)
            .orderBy(total.desc())
            .select(CustomerTable.firstName, sum(PurchaseTable.price) `as` total)
            .performWith(cxn)
            .map { row -> row.labels.values.map { row[it] } }
            .toList()

        val expectedTotals = listOf(
            listOf("Jane", 8150),
            listOf("Bob", 700),
        )

        assertListOfListsEquals(expectedTotals, actualTotals)

        val whoDidntShopAtHardware = CustomerTable
            .leftJoin(PurchaseTable
                .innerJoin(ShopTable, PurchaseTable.shop eq ShopTable.id)
                .where(ShopTable.name eq "Hardware")
                .select(PurchaseTable, ShopTable)
                .subquery(),
                CustomerTable.id eq PurchaseTable.customer
            )
            .where(PurchaseTable.id.isNull())
            .select(CustomerTable.firstName)
            .performWith(cxn)
            .map { it[CustomerTable.firstName] }
            .single()

        assert("Bob" == whoDidntShopAtHardware)

        val mp = alias()

        val expectedMostExpensiveByStore = listOf(
            listOf("Groceries", "Pear"),
            listOf("Hardware", "Hammer"),
            listOf("Stationary", "Pen")
        )

        val actualMostExpensiveByStore = PurchaseTable
            .groupBy(PurchaseTable.shop)
            .select(
                PurchaseTable.shop,
                max(PurchaseTable.price) `as` PurchaseTable.price
            )
            .subquery()
            .alias(mp)
            .innerJoin(PurchaseTable, (mp[PurchaseTable.shop] eq PurchaseTable.shop).and(
                mp[PurchaseTable.price] eq PurchaseTable.price
            ))
            .innerJoin(ShopTable, PurchaseTable.shop eq ShopTable.id)
            .orderBy(ShopTable.name)
            .select(ShopTable, PurchaseTable)
            .performWith(cxn)
            .map { listOf(it[ShopTable.name], it[PurchaseTable.product]) }
            .toList()

        assertListOfListsEquals(expectedMostExpensiveByStore, actualMostExpensiveByStore)
    }

    @Test
    fun `update through not exists`() {
        val cxn = ConnectionWithDialect(
            H2Dialect(),
            DriverManager.getConnection("jdbc:h2:mem:")
        )

        createAndPopulate(cxn)

        CustomerTable
            .where(notExists(PurchaseTable
                .innerJoin(ShopTable, PurchaseTable.shop eq ShopTable.id)
                .where(ShopTable.name eq "Hardware")
                .where(CustomerTable.id eq PurchaseTable.customer)
                .select(PurchaseTable.id)
            ))
            .update(
                CustomerTable.firstName setTo "Bawb",
                CustomerTable.lastName setTo CustomerTable.firstName
            )
            .performWith(cxn)

        CustomerTable
            .where(CustomerTable.firstName eq "Bawb")
            .where(CustomerTable.lastName eq "Bob")
            .select(CustomerTable)
            .performWith(cxn)
            .single()
    }

    @Test
    fun `table diff`() {
        val cxn = ConnectionWithDialect(
            H2Dialect(),
            DriverManager.getConnection("jdbc:h2:mem:")
        )

        cxn.ddl(createTables(
            CustomerTable
        ))

        val changedCustomerTable = Table("Customer").apply {
            column("id", DataType.INT32.autoIncrement())

            column("firstName", DataType.VARCHAR(100))
            column("lastName", DataType.VARCHAR(100))
        }

        val diff = TableDiffer(cxn.jdbc.metaData).diffTable(
            changedCustomerTable
        )

        println(diff)
    }
}