package com.localcloud.admin.bigtablesql;

import com.localcloud.admin.bigtablesql.SqlAstNode.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlParserTest {

    private SqlAstNode parse(String sql) {
        var tokens = new SqlTokenizer(sql).tokenize();
        return new SqlParser(tokens).parseStatement();
    }

    // ─── SELECT ──────────────────────────────────────────────────────────

    @Test
    void selectStar() {
        var stmt = (SelectStatement) parse("SELECT * FROM \"jay-instance.jay-table\"");
        assertEquals("jay-instance", stmt.table().instance());
        assertEquals("jay-table", stmt.table().table());
        assertNull(stmt.columns());
        assertNull(stmt.where());
        assertEquals(-1, stmt.limit());
    }

    @Test
    void selectWithLimit() {
        var stmt = (SelectStatement) parse("SELECT * FROM \"inst.tbl\" LIMIT 50");
        assertEquals(50, stmt.limit());
    }

    @Test
    void selectColumns() {
        var stmt = (SelectStatement) parse("SELECT cf1:name, cf2:email FROM \"inst.tbl\"");
        assertNotNull(stmt.columns());
        assertEquals(2, stmt.columns().size());
        var col0 = (ColumnRefExpr) stmt.columns().get(0).expr();
        assertEquals("cf1", col0.family());
        assertEquals("name", col0.qualifier());
        var col1 = (ColumnRefExpr) stmt.columns().get(1).expr();
        assertEquals("cf2", col1.family());
        assertEquals("email", col1.qualifier());
    }

    @Test
    void selectWhereEquals() {
        var stmt = (SelectStatement) parse("SELECT * FROM \"inst.tbl\" WHERE rowkey = 'user#001'");
        assertInstanceOf(BinaryOp.class, stmt.where());
        var op = (BinaryOp) stmt.where();
        assertEquals("=", op.operator());
        assertInstanceOf(ColumnRefExpr.class, op.left());
        assertEquals("rowkey", ((ColumnRefExpr) op.left()).qualifier());
        assertEquals("user#001", ((StringLiteral) op.right()).value());
    }

    @Test
    void selectWhereBetween() {
        var stmt = (SelectStatement) parse("SELECT * FROM \"inst.tbl\" WHERE rowkey BETWEEN 'a' AND 'z'");
        assertInstanceOf(BetweenExpr.class, stmt.where());
        var between = (BetweenExpr) stmt.where();
        assertEquals("a", ((StringLiteral) between.low()).value());
        assertEquals("z", ((StringLiteral) between.high()).value());
    }

    @Test
    void selectWhereLike() {
        var stmt = (SelectStatement) parse("SELECT * FROM \"inst.tbl\" WHERE rowkey LIKE 'user#%'");
        assertInstanceOf(LikeExpr.class, stmt.where());
        assertEquals("user#%", ((LikeExpr) stmt.where()).pattern());
    }

    @Test
    void selectWhereIn() {
        var stmt = (SelectStatement) parse("SELECT * FROM \"inst.tbl\" WHERE rowkey IN ('a', 'b', 'c')");
        assertInstanceOf(InExpr.class, stmt.where());
        var in = (InExpr) stmt.where();
        assertEquals(3, in.values().size());
    }

    @Test
    void selectUnquotedTable() {
        var stmt = (SelectStatement) parse("SELECT * FROM inst.tbl");
        assertEquals("inst", stmt.table().instance());
        assertEquals("tbl", stmt.table().table());
    }

    // ─── INSERT ──────────────────────────────────────────────────────────

    @Test
    void insert() {
        var stmt = (InsertStatement) parse(
                "INSERT INTO \"inst.tbl\" (rowkey, cf1:name, cf1:email) VALUES ('row1', 'Jay', 'jay@ex.com')");
        assertEquals("inst", stmt.table().instance());
        assertEquals("tbl", stmt.table().table());
        assertEquals(3, stmt.columnNames().size());
        assertEquals("rowkey", stmt.columnNames().get(0));
        assertEquals("cf1:name", stmt.columnNames().get(1));
        assertEquals("Jay", stmt.values().get(1));
    }

    // ─── UPDATE ──────────────────────────────────────────────────────────

    @Test
    void updateSimple() {
        var stmt = (UpdateStatement) parse(
                "UPDATE \"inst.tbl\" SET cf1:name = 'NewName' WHERE rowkey = 'row1'");
        assertEquals(1, stmt.assignments().size());
        assertTrue(stmt.assignments().containsKey("cf1:name"));
        assertEquals("NewName", ((StringLiteral) stmt.assignments().get("cf1:name")).value());
    }

    @Test
    void updateIncrement() {
        var stmt = (UpdateStatement) parse(
                "UPDATE \"inst.tbl\" SET cf1:counter = cf1:counter + 1 WHERE rowkey = 'row1'");
        var expr = stmt.assignments().get("cf1:counter");
        assertInstanceOf(BinaryOp.class, expr);
        assertEquals("+", ((BinaryOp) expr).operator());
        assertInstanceOf(ColumnRefExpr.class, ((BinaryOp) expr).left());
        assertInstanceOf(NumberLiteral.class, ((BinaryOp) expr).right());
    }

    // ─── DELETE ──────────────────────────────────────────────────────────

    @Test
    void deleteByKey() {
        var stmt = (DeleteStatement) parse("DELETE FROM \"inst.tbl\" WHERE rowkey = 'row1'");
        assertInstanceOf(BinaryOp.class, stmt.where());
    }

    @Test
    void deleteBetween() {
        var stmt = (DeleteStatement) parse("DELETE FROM \"inst.tbl\" WHERE rowkey BETWEEN 'a' AND 'z'");
        assertInstanceOf(BetweenExpr.class, stmt.where());
    }

    // ─── DDL ─────────────────────────────────────────────────────────────

    @Test
    void createTable() {
        var stmt = (CreateTableStatement) parse(
                "CREATE TABLE \"inst.tbl\" (FAMILY cf1, FAMILY cf2 MAX_VERSIONS 3)");
        assertEquals(2, stmt.families().size());
        assertEquals("cf1", stmt.families().get(0).name());
        assertEquals(-1, stmt.families().get(0).maxVersions());
        assertEquals("cf2", stmt.families().get(1).name());
        assertEquals(3, stmt.families().get(1).maxVersions());
    }

    @Test
    void dropTable() {
        var stmt = (DropTableStatement) parse("DROP TABLE \"inst.tbl\"");
        assertEquals("inst", stmt.table().instance());
        assertEquals("tbl", stmt.table().table());
    }

    @Test
    void alterTableAdd() {
        var stmt = (AlterTableStatement) parse("ALTER TABLE \"inst.tbl\" ADD FAMILY cf3");
        assertEquals(1, stmt.modifications().size());
        assertEquals("ADD", stmt.modifications().get(0).action());
        assertEquals("cf3", stmt.modifications().get(0).familyName());
    }

    @Test
    void alterTableDrop() {
        var stmt = (AlterTableStatement) parse("ALTER TABLE \"inst.tbl\" DROP FAMILY cf2");
        assertEquals("DROP", stmt.modifications().get(0).action());
    }

    // ─── SHOW / DESCRIBE ─────────────────────────────────────────────────

    @Test
    void showTables() {
        var stmt = (ShowTablesStatement) parse("SHOW TABLES");
        assertNull(stmt.instance());
    }

    @Test
    void showTablesForInstance() {
        var stmt = (ShowTablesStatement) parse("SHOW TABLES 'my-instance'");
        assertEquals("my-instance", stmt.instance());
    }

    @Test
    void describeTable() {
        var stmt = (DescribeTableStatement) parse("DESCRIBE \"inst.tbl\"");
        assertEquals("inst", stmt.table().instance());
    }

    // ─── Error cases ─────────────────────────────────────────────────────

    @Test
    void invalidStatement() {
        assertThrows(BigtableSqlException.class, () -> parse("TRUNCATE TABLE foo"));
    }

    @Test
    void mismatchedValues() {
        assertThrows(BigtableSqlException.class, () -> parse(
                "INSERT INTO \"i.t\" (rowkey, cf1:name) VALUES ('a')"));
    }

    @Test
    void trailingSemicolon() {
        var stmt = (SelectStatement) parse("SELECT * FROM \"inst.tbl\";");
        assertNotNull(stmt);
    }
}
