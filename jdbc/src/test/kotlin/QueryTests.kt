import mfwgenerics.kotq.data.INTEGER
import mfwgenerics.kotq.data.VARCHAR
import mfwgenerics.kotq.ddl.Table
import mfwgenerics.kotq.dsl.*
import mfwgenerics.kotq.expr.`as`
import mfwgenerics.kotq.jdbc.ConnectionWithDialect
import mfwgenerics.kotq.jdbc.performWith
import mfwgenerics.kotq.setTo
import kotlin.test.Test

abstract class QueryTests: ProvideTestDatabase {
    @Test
    fun `triangular numbers from values clause subquery`() = withCxn { cxn ->
        val number = name<Int>("number")
        val summed = name<Int>("sumUnder")

        /* need this cast to workaround H2 bug (? in VALUES aren't typed correctly) */
        val castNumber = cast(number, INTEGER)

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
            .`as`(alias)
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
    }

    object ShopTable: Table("Shop") {
        val id = column("id", INTEGER.autoIncrement())

        val name = column("name", VARCHAR(100))

        init {
            primaryKey(keys(id))
        }
    }

    object CustomerTable: Table("Customer") {
        val id = column("id", INTEGER.autoIncrement())

        val firstName = column("firstName", VARCHAR(100))
        val lastName = column("lastName", VARCHAR(100))

        init {
            primaryKey(keys(id))
        }
    }

    object PurchaseTable: Table("Purchase") {
        val id = column("id", INTEGER.autoIncrement().primaryKey())

        val shop = column("shop", INTEGER.reference(ShopTable.id))
        val customer = column("customer", INTEGER.reference(CustomerTable.id))

        val product = column("product", VARCHAR(200))

        val price = column("price", INTEGER)
        val discount = column("discount", INTEGER.nullable())
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
        cxn.createTable(
            ShopTable,
            CustomerTable,
            PurchaseTable
        )

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
    fun `stringy joins`() = withCxn { cxn ->
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
            .`as`(mp)
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
    fun `update through not exists`() = withCxn { cxn ->
        createAndPopulate(cxn)

        CustomerTable
            .where(notExists(PurchaseTable
                .innerJoin(ShopTable, PurchaseTable.shop eq ShopTable.id)
                .where(ShopTable.name eq "Hardware")
                .where(CustomerTable.id eq PurchaseTable.customer)
                .select(PurchaseTable.id)
            ))
            .update(
                CustomerTable.lastName setTo CustomerTable.firstName,
                CustomerTable.firstName setTo "Bawb"
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
    fun `insert from select and subquery comparisons`() = withCxn { cxn ->
        createAndPopulate(cxn)

        val (bobId, janeId) = CustomerTable
            .where(CustomerTable.firstName inValues listOf("Bob", "Jane"))
            .orderBy(CustomerTable.firstName)
            .select(CustomerTable.id)
            .performWith(cxn)
            .map { it[CustomerTable.id]!! }
            .toList()

        PurchaseTable
            .insert(PurchaseTable
                .where((PurchaseTable.id eq bobId).and(PurchaseTable.product eq "Pear"))
                .select(
                    PurchaseTable.shop,
                    PurchaseTable.customer,

                    literal("NanoPear") `as` PurchaseTable.product,
                    cast(PurchaseTable.price / 100, INTEGER) `as` PurchaseTable.price,

                    PurchaseTable.discount
                )
            )
            .performWith(cxn)

        val janesPurchasePrices = PurchaseTable
            .where(PurchaseTable.customer eq janeId)
            .select(PurchaseTable.price)

        val cheaperThanAll = PurchaseTable
            .where((PurchaseTable.customer eq bobId)
                .and(PurchaseTable.price less all(janesPurchasePrices))
            )
            .orderBy(PurchaseTable.product)
            .select(PurchaseTable.product)
            .performWith(cxn)
            .map { it[PurchaseTable.product] }
            .toList()

        val cheaperThanAny = PurchaseTable
            .where((PurchaseTable.customer eq bobId)
                .and(PurchaseTable.price less any(janesPurchasePrices))
            )
            .orderBy(PurchaseTable.product)
            .select(PurchaseTable.product)
            .performWith(cxn)
            .map { it[PurchaseTable.product] }
            .toList()

        assertListEquals(cheaperThanAll, listOf("NanoPear"))
        assertListEquals(cheaperThanAny, listOf("NanoPear", "Pear", "Pen"))
    }

    @Test
    fun `join to cte`() = withCxn { cxn ->
        createAndPopulate(cxn)

        val alias = alias()
        val cte = cte()

        val rows = CustomerTable
            .with(cte `as` PurchaseTable
                .select(
                    PurchaseTable,
                    -PurchaseTable.price `as` PurchaseTable.price
                )
            )
            .innerJoin(cte, CustomerTable.id eq PurchaseTable.customer)
            .leftJoin(cte.`as`(alias), (CustomerTable.id eq alias[PurchaseTable.customer])
                .and(PurchaseTable.price less -600))
            .orderBy(
                CustomerTable.id,
                PurchaseTable.id,
                alias[PurchaseTable.id]
            )
            .select(cte, CustomerTable.firstName, cte.`as`(alias))
            .performWith(cxn)
            .map { row ->
                row.labels.values.map { row[it] }
            }
            .toList()

        val expected = listOf(
            listOf(1, 2, 1, "Apple", -150, 20, "Jane", null, null, null, null, null, null),
            listOf(3, 1, 1, "Hammer", -8000, null, "Jane", 1, 2, 1, "Apple", -150, 20),
            listOf(3, 1, 1, "Hammer", -8000, null, "Jane", 3, 1, 1, "Hammer", -8000, null),
            listOf(2, 2, 2, "Pear", -200, null, "Bob", null, null, null, null, null, null),
            listOf(4, 3, 2, "Pen", -500, null, "Bob", null, null, null, null, null, null)
        )

        assertListOfListsEquals(
            expected,
            rows
        )
    }

    @Test
    fun `union all and count`() = withCxn { cxn ->
        createAndPopulate(cxn)

        val count = name<Int>()

        val purchaseCount = PurchaseTable
            .select(count(literal(1)) `as` count)
            .performWith(cxn)
            .single()[count]!!

        val doubleCount = PurchaseTable
            .unionAll(PurchaseTable)
            .selectAll()
            .performWith(cxn)
            .count()

        assert(purchaseCount == 4)
        assert(doubleCount == 8)
    }

    enum class NumberEnum {
        ONE, TWO, THREE
    }

    enum class ColorEnum {
        RED, GREEN, BLUE
    }

    enum class FruitEnum {
        APPLE, BANANA, ORANGE
    }

    object MappingsTable: Table("Customer") {
        val number = column("number", VARCHAR(100).map({ NumberEnum.valueOf(it) }, { "$it" }))
        val color = column("color", VARCHAR(101).map({ ColorEnum.valueOf(it) }, { "$it" }))
        val fruit = column("fruit", INTEGER.map({ FruitEnum.values()[it] }, { it.ordinal }))
    }

    @Test
    fun `inserting and selecting from mapped columns`() = withCxn { cxn ->
        cxn.createTable(MappingsTable)

        MappingsTable
            .insert(values(
                rowOf(
                    MappingsTable.number setTo NumberEnum.TWO,
                    MappingsTable.color setTo ColorEnum.BLUE,
                    MappingsTable.fruit setTo FruitEnum.BANANA
                )
            ))
            .performWith(cxn)

        val result = MappingsTable
            .selectAll()
            .performWith(cxn)
            .single()

        assert(result[MappingsTable.number] == NumberEnum.TWO)
        assert(result[MappingsTable.color] == ColorEnum.BLUE)
        assert(result[MappingsTable.fruit] == FruitEnum.BANANA)
    }
}