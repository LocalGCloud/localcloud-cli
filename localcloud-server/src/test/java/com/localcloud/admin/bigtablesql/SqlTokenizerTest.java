package com.localcloud.admin.bigtablesql;

import com.localcloud.admin.bigtablesql.SqlToken.TokenType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlTokenizerTest {

    private List<SqlToken> tokenize(String sql) {
        return new SqlTokenizer(sql).tokenize();
    }

    @Test
    void selectStar() {
        var tokens = tokenize("SELECT * FROM \"inst.tbl\"");
        assertEquals(TokenType.SELECT, tokens.get(0).type());
        assertEquals(TokenType.STAR, tokens.get(1).type());
        assertEquals(TokenType.FROM, tokens.get(2).type());
        assertEquals(TokenType.QUOTED_IDENTIFIER, tokens.get(3).type());
        assertEquals("inst.tbl", tokens.get(3).value());
        assertEquals(TokenType.EOF, tokens.get(4).type());
    }

    @Test
    void selectWithLimit() {
        var tokens = tokenize("select * from \"i.t\" LIMIT 50");
        assertEquals(TokenType.SELECT, tokens.get(0).type());
        assertEquals(TokenType.LIMIT, tokens.get(4).type());
        assertEquals("50", tokens.get(5).value());
    }

    @Test
    void columnReference() {
        var tokens = tokenize("SELECT cf1:name, cf2:email FROM \"i.t\"");
        assertEquals(TokenType.IDENTIFIER, tokens.get(1).type());
        assertEquals("cf1", tokens.get(1).value());
        assertEquals(TokenType.COLON, tokens.get(2).type());
        assertEquals(TokenType.IDENTIFIER, tokens.get(3).type());
        assertEquals("name", tokens.get(3).value());
        assertEquals(TokenType.COMMA, tokens.get(4).type());
    }

    @Test
    void stringLiteral() {
        var tokens = tokenize("WHERE rowkey = 'hello world'");
        assertEquals(TokenType.STRING, tokens.get(3).type());
        assertEquals("hello world", tokens.get(3).value());
    }

    @Test
    void stringEscape() {
        var tokens = tokenize("'it''s'");
        assertEquals(TokenType.STRING, tokens.get(0).type());
        assertEquals("it's", tokens.get(0).value());
    }

    @Test
    void operators() {
        var tokens = tokenize("a <= b <> c >= d != e");
        assertEquals(TokenType.LTE, tokens.get(1).type());
        assertEquals(TokenType.NE, tokens.get(3).type());
        assertEquals(TokenType.GTE, tokens.get(5).type());
        assertEquals(TokenType.NE, tokens.get(7).type());
    }

    @Test
    void insertKeywords() {
        var tokens = tokenize("INSERT INTO \"i.t\" (rowkey) VALUES ('v')");
        assertEquals(TokenType.INSERT, tokens.get(0).type());
        assertEquals(TokenType.INTO, tokens.get(1).type());
        assertEquals(TokenType.VALUES, tokens.get(6).type());
    }

    @Test
    void createTable() {
        var tokens = tokenize("CREATE TABLE \"i.t\" (FAMILY cf1 MAX_VERSIONS 3)");
        assertEquals(TokenType.CREATE, tokens.get(0).type());
        assertEquals(TokenType.TABLE, tokens.get(1).type());
        assertEquals(TokenType.FAMILY, tokens.get(4).type());
        assertEquals(TokenType.MAX_VERSIONS, tokens.get(6).type());
    }

    @Test
    void betweenAndLike() {
        var tokens = tokenize("WHERE rowkey BETWEEN 'a' AND 'z'");
        assertEquals(TokenType.BETWEEN, tokens.get(2).type());
        assertEquals(TokenType.AND, tokens.get(4).type());
    }

    @Test
    void commentSkipped() {
        var tokens = tokenize("SELECT * -- this is a comment\nFROM \"i.t\"");
        assertEquals(TokenType.SELECT, tokens.get(0).type());
        assertEquals(TokenType.STAR, tokens.get(1).type());
        assertEquals(TokenType.FROM, tokens.get(2).type());
    }

    @Test
    void hyphenatedIdentifier() {
        var tokens = tokenize("my-instance");
        assertEquals(TokenType.IDENTIFIER, tokens.get(0).type());
        assertEquals("my-instance", tokens.get(0).value());
    }
}
