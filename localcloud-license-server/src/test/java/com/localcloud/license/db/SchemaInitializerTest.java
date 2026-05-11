package com.localcloud.license.db;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchemaInitializerTest {

    @Test
    void schemaCreatesAllTables() throws Exception {
        var ds = createH2DataSource();
        new SchemaInitializer(ds).initialize();

        try (var conn = ds.getConnection(); var stmt = conn.createStatement()) {
            stmt.executeQuery("SELECT COUNT(*) FROM users").next();
            stmt.executeQuery("SELECT COUNT(*) FROM api_keys").next();
            stmt.executeQuery("SELECT COUNT(*) FROM devices").next();
            stmt.executeQuery("SELECT COUNT(*) FROM trials").next();
        }
    }

    @Test
    void schemaIsIdempotent() throws Exception {
        var ds = createH2DataSource();
        var init = new SchemaInitializer(ds);
        init.initialize();
        assertDoesNotThrow(init::initialize);
    }

    private javax.sql.DataSource createH2DataSource() {
        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:schema_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }
}
