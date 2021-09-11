import mfwgenerics.kotq.h2.H2Database
import mfwgenerics.kotq.h2.H2Dialect
import mfwgenerics.kotq.h2.H2TypeMappings
import mfwgenerics.kotq.jdbc.JdbcDatabase
import java.sql.DriverManager
import kotlin.test.Test

class H2DateTimeTests: DateTimeTests() {
    override fun connect(db: String): JdbcDatabase = H2Database(db)

    @Test
    fun empty() { }
}