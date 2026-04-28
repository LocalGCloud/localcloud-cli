package com.localcloud.admin.bigtablesql;

import com.localcloud.admin.bigtablesql.SqlAstNode.*;
import com.localcloud.admin.bigtablesql.SqlToken.TokenType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recursive descent parser for the Bigtable SQL dialect.
 * <p>
 * Supported statements:
 * <ul>
 *   <li>SELECT [columns|*] FROM "instance.table" [WHERE ...] [LIMIT n]</li>
 *   <li>INSERT INTO "instance.table" (cols) VALUES (vals)</li>
 *   <li>UPDATE "instance.table" SET col = val [, ...] WHERE rowkey = 'key'</li>
 *   <li>DELETE FROM "instance.table" WHERE rowkey = 'key'</li>
 *   <li>CREATE TABLE "instance.table" (FAMILY cf1 [MAX_VERSIONS n], ...)</li>
 *   <li>DROP TABLE "instance.table"</li>
 *   <li>ALTER TABLE "instance.table" ADD|DROP FAMILY name</li>
 *   <li>SHOW TABLES ["instance"]</li>
 *   <li>DESCRIBE "instance.table"</li>
 * </ul>
 */
public final class SqlParser {

    private final List<SqlToken> tokens;
    private int pos;

    public SqlParser(List<SqlToken> tokens) {
        this.tokens = tokens;
        this.pos = 0;
    }

    public SqlAstNode parseStatement() {
        SqlToken t = peek();
        SqlAstNode result = switch (t.type()) {
            case SELECT -> parseSelect();
            case INSERT -> parseInsert();
            case UPDATE -> parseUpdate();
            case DELETE -> parseDelete();
            case CREATE -> parseCreate();
            case DROP -> parseDrop();
            case ALTER -> parseAlter();
            case SHOW -> parseShow();
            case DESCRIBE -> parseDescribe();
            default -> throw error("Expected SQL statement (SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, ALTER, SHOW, DESCRIBE)");
        };
        // Optional trailing semicolon
        if (peek().type() == TokenType.SEMICOLON) advance();
        return result;
    }

    // ─── SELECT ──────────────────────────────────────────────────────────

    private SelectStatement parseSelect() {
        expect(TokenType.SELECT);

        // Optional DISTINCT
        boolean distinct = false;
        if (peek().type() == TokenType.DISTINCT) {
            advance();
            distinct = true;
        }

        // Columns: * or expression [AS alias], ...
        List<SelectColumn> columns = null;
        if (peek().type() == TokenType.STAR) {
            advance(); // consume *
        } else {
            columns = parseSelectColumnList();
        }

        expect(TokenType.FROM);
        TableRef table = parseTableRef();

        // Optional WHERE
        Expression where = null;
        if (peek().type() == TokenType.WHERE) {
            advance();
            where = parseExpression();
        }

        // Optional GROUP BY
        List<Expression> groupBy = null;
        if (peek().type() == TokenType.GROUP) {
            advance();
            expect(TokenType.BY);
            groupBy = parseExpressionList();
        }

        // Optional HAVING
        Expression having = null;
        if (peek().type() == TokenType.HAVING) {
            advance();
            having = parseExpression();
        }

        // Optional ORDER BY
        List<OrderByClause> orderBy = null;
        if (peek().type() == TokenType.ORDER) {
            advance();
            expect(TokenType.BY);
            orderBy = parseOrderByList();
        }

        // Optional LIMIT
        int limit = -1;
        if (peek().type() == TokenType.LIMIT) {
            advance();
            limit = Integer.parseInt(expect(TokenType.NUMBER).value());
        }

        // Optional OFFSET
        int offset = -1;
        if (peek().type() == TokenType.OFFSET) {
            advance();
            offset = Integer.parseInt(expect(TokenType.NUMBER).value());
        }

        return new SelectStatement(distinct, table, columns, where, groupBy, having, orderBy, limit, offset);
    }

    private List<SelectColumn> parseSelectColumnList() {
        List<SelectColumn> columns = new ArrayList<>();
        columns.add(parseSelectColumn());
        while (peek().type() == TokenType.COMMA) {
            advance();
            columns.add(parseSelectColumn());
        }
        return columns;
    }

    private SelectColumn parseSelectColumn() {
        Expression expr = parseExpression();
        String alias = null;
        if (peek().type() == TokenType.AS) {
            advance();
            alias = expectIdentifier();
        }
        return new SelectColumn(expr, alias);
    }

    private List<Expression> parseExpressionList() {
        List<Expression> exprs = new ArrayList<>();
        exprs.add(parseExpression());
        while (peek().type() == TokenType.COMMA) {
            advance();
            exprs.add(parseExpression());
        }
        return exprs;
    }

    private List<OrderByClause> parseOrderByList() {
        List<OrderByClause> list = new ArrayList<>();
        list.add(parseOrderByClause());
        while (peek().type() == TokenType.COMMA) {
            advance();
            list.add(parseOrderByClause());
        }
        return list;
    }

    private OrderByClause parseOrderByClause() {
        Expression expr = parseExpression();
        boolean ascending = true;
        if (peek().type() == TokenType.ASC) {
            advance();
        } else if (peek().type() == TokenType.DESC) {
            advance();
            ascending = false;
        }
        return new OrderByClause(expr, ascending);
    }

    // ─── INSERT ──────────────────────────────────────────────────────────

    private InsertStatement parseInsert() {
        expect(TokenType.INSERT);
        expect(TokenType.INTO);
        TableRef table = parseTableRef();

        // Column names: (rowkey, cf1:name, cf1:email)
        expect(TokenType.LPAREN);
        List<String> columnNames = new ArrayList<>();
        columnNames.add(parseColumnName());
        while (peek().type() == TokenType.COMMA) {
            advance();
            columnNames.add(parseColumnName());
        }
        expect(TokenType.RPAREN);

        // VALUES (val1, val2, val3)
        expect(TokenType.VALUES);
        expect(TokenType.LPAREN);
        List<String> values = new ArrayList<>();
        values.add(parseValueLiteral());
        while (peek().type() == TokenType.COMMA) {
            advance();
            values.add(parseValueLiteral());
        }
        expect(TokenType.RPAREN);

        if (columnNames.size() != values.size()) {
            throw error("Column count (" + columnNames.size() + ") does not match value count (" + values.size() + ")");
        }

        return new InsertStatement(table, columnNames, values);
    }

    // ─── UPDATE ──────────────────────────────────────────────────────────

    private UpdateStatement parseUpdate() {
        expect(TokenType.UPDATE);
        TableRef table = parseTableRef();
        expect(TokenType.SET);

        Map<String, Expression> assignments = new LinkedHashMap<>();
        parseAssignment(assignments);
        while (peek().type() == TokenType.COMMA) {
            advance();
            parseAssignment(assignments);
        }

        expect(TokenType.WHERE);
        Expression where = parseExpression();

        return new UpdateStatement(table, assignments, where);
    }

    private void parseAssignment(Map<String, Expression> assignments) {
        String col = parseColumnName();
        expect(TokenType.EQ);
        Expression value = parseExpression();
        assignments.put(col, value);
    }

    // ─── DELETE ──────────────────────────────────────────────────────────

    private DeleteStatement parseDelete() {
        expect(TokenType.DELETE);
        expect(TokenType.FROM);
        TableRef table = parseTableRef();
        expect(TokenType.WHERE);
        Expression where = parseExpression();
        return new DeleteStatement(table, where);
    }

    // ─── CREATE TABLE ────────────────────────────────────────────────────

    private CreateTableStatement parseCreate() {
        expect(TokenType.CREATE);
        expect(TokenType.TABLE);
        TableRef table = parseTableRef();

        expect(TokenType.LPAREN);
        List<ColumnFamilyDef> families = new ArrayList<>();
        families.add(parseFamilyDef());
        while (peek().type() == TokenType.COMMA) {
            advance();
            families.add(parseFamilyDef());
        }
        expect(TokenType.RPAREN);

        return new CreateTableStatement(table, families);
    }

    private ColumnFamilyDef parseFamilyDef() {
        expect(TokenType.FAMILY);
        String name = expectIdentifier();
        int maxVersions = -1;
        if (peek().type() == TokenType.MAX_VERSIONS) {
            advance();
            maxVersions = Integer.parseInt(expect(TokenType.NUMBER).value());
        }
        return new ColumnFamilyDef(name, maxVersions);
    }

    // ─── DROP TABLE ──────────────────────────────────────────────────────

    private DropTableStatement parseDrop() {
        expect(TokenType.DROP);
        expect(TokenType.TABLE);
        TableRef table = parseTableRef();
        return new DropTableStatement(table);
    }

    // ─── ALTER TABLE ─────────────────────────────────────────────────────

    private AlterTableStatement parseAlter() {
        expect(TokenType.ALTER);
        expect(TokenType.TABLE);
        TableRef table = parseTableRef();

        List<FamilyModification> mods = new ArrayList<>();
        mods.add(parseFamilyModification());
        while (peek().type() == TokenType.COMMA) {
            advance();
            mods.add(parseFamilyModification());
        }
        return new AlterTableStatement(table, mods);
    }

    private FamilyModification parseFamilyModification() {
        String action;
        if (peek().type() == TokenType.ADD) {
            action = "ADD";
            advance();
        } else if (peek().type() == TokenType.DROP) {
            action = "DROP";
            advance();
        } else {
            throw error("Expected ADD or DROP");
        }
        expect(TokenType.FAMILY);
        String familyName = expectIdentifier();
        int maxVersions = -1;
        if ("ADD".equals(action) && peek().type() == TokenType.MAX_VERSIONS) {
            advance();
            maxVersions = Integer.parseInt(expect(TokenType.NUMBER).value());
        }
        return new FamilyModification(action, familyName, maxVersions);
    }

    // ─── SHOW TABLES ─────────────────────────────────────────────────────

    private ShowTablesStatement parseShow() {
        expect(TokenType.SHOW);
        expect(TokenType.TABLES);
        String instance = null;
        if (peek().type() == TokenType.STRING || peek().type() == TokenType.QUOTED_IDENTIFIER
                || peek().type() == TokenType.IDENTIFIER) {
            instance = advance().value();
        }
        return new ShowTablesStatement(instance);
    }

    // ─── DESCRIBE ────────────────────────────────────────────────────────

    private DescribeTableStatement parseDescribe() {
        expect(TokenType.DESCRIBE);
        TableRef table = parseTableRef();
        return new DescribeTableStatement(table);
    }

    // ─── Expression Parsing (recursive descent) ──────────────────────────

    private Expression parseExpression() {
        return parseOr();
    }

    private Expression parseOr() {
        Expression left = parseAnd();
        while (peek().type() == TokenType.OR) {
            advance();
            Expression right = parseAnd();
            left = new BinaryOp("OR", left, right);
        }
        return left;
    }

    private Expression parseAnd() {
        Expression left = parseComparison();
        while (peek().type() == TokenType.AND) {
            advance();
            Expression right = parseComparison();
            left = new BinaryOp("AND", left, right);
        }
        return left;
    }

    private Expression parseComparison() {
        Expression left = parseAdditive();

        // IS [NOT] NULL
        if (peek().type() == TokenType.IS) {
            advance();
            boolean negated = false;
            if (peek().type() == TokenType.NOT) {
                advance();
                negated = true;
            }
            expect(TokenType.NULL);
            return new IsNullExpr(left, negated);
        }

        // NOT BETWEEN / NOT LIKE / NOT IN
        boolean negated = false;
        if (peek().type() == TokenType.NOT) {
            // lookahead: only consume NOT if followed by BETWEEN, LIKE, or IN
            int savedPos = pos;
            advance();
            TokenType next = peek().type();
            if (next == TokenType.BETWEEN || next == TokenType.LIKE || next == TokenType.IN) {
                negated = true;
            } else {
                pos = savedPos; // put NOT back
            }
        }

        // BETWEEN expr AND expr
        if (peek().type() == TokenType.BETWEEN) {
            advance();
            Expression low = parseAdditive();
            expect(TokenType.AND);
            Expression high = parseAdditive();
            Expression result = new BetweenExpr(left, low, high);
            return negated ? new BinaryOp("NOT", result, null) : result;
        }

        // LIKE 'pattern'
        if (peek().type() == TokenType.LIKE) {
            advance();
            String pattern = expect(TokenType.STRING).value();
            Expression result = new LikeExpr(left, pattern);
            return negated ? new BinaryOp("NOT", result, null) : result;
        }

        // IN (val1, val2, ...)
        if (peek().type() == TokenType.IN) {
            advance();
            expect(TokenType.LPAREN);
            List<Expression> values = new ArrayList<>();
            values.add(parseAdditive());
            while (peek().type() == TokenType.COMMA) {
                advance();
                values.add(parseAdditive());
            }
            expect(TokenType.RPAREN);
            Expression result = new InExpr(left, values);
            return negated ? new BinaryOp("NOT", result, null) : result;
        }

        // Standard comparison operators
        TokenType op = peek().type();
        if (op == TokenType.EQ || op == TokenType.NE || op == TokenType.LT ||
                op == TokenType.GT || op == TokenType.LTE || op == TokenType.GTE) {
            String opStr = advance().value();
            Expression right = parseAdditive();
            return new BinaryOp(opStr, left, right);
        }

        return left;
    }

    private Expression parseAdditive() {
        Expression left = parseMultiplicative();
        while (peek().type() == TokenType.PLUS || peek().type() == TokenType.MINUS) {
            String op = advance().value();
            Expression right = parseMultiplicative();
            left = new BinaryOp(op, left, right);
        }
        return left;
    }

    private Expression parseMultiplicative() {
        Expression left = parsePrimary();
        while (peek().type() == TokenType.STAR || peek().type() == TokenType.SLASH
                || peek().type() == TokenType.PERCENT) {
            String op = advance().value();
            Expression right = parsePrimary();
            left = new BinaryOp(op, left, right);
        }
        return left;
    }

    private Expression parsePrimary() {
        SqlToken t = peek();
        return switch (t.type()) {
            case STRING -> { advance(); yield new StringLiteral(t.value()); }
            case NUMBER -> { advance(); yield new NumberLiteral(Long.parseLong(t.value())); }
            case FLOAT -> { advance(); yield new FloatLiteral(Double.parseDouble(t.value())); }
            case NULL -> { advance(); yield new NullLiteral(); }
            case TRUE -> { advance(); yield new BooleanLiteral(true); }
            case FALSE -> { advance(); yield new BooleanLiteral(false); }
            case CASE -> parseCaseExpr();
            case CAST -> parseCastExpr();
            case IF -> parseFunctionCallKeyword("IF");
            case COALESCE -> parseFunctionCallKeyword("COALESCE");
            case NULLIF -> parseFunctionCallKeyword("NULLIF");
            case STAR -> { advance(); yield new StarExpr(); }
            case MINUS -> {
                advance();
                Expression inner = parsePrimary();
                if (inner instanceof NumberLiteral n) yield new NumberLiteral(-n.value());
                if (inner instanceof FloatLiteral f) yield new FloatLiteral(-f.value());
                yield new BinaryOp("*", new NumberLiteral(-1), inner);
            }
            case NOT -> {
                advance();
                Expression inner = parsePrimary();
                yield new BinaryOp("NOT", inner, null);
            }
            case IDENTIFIER -> parseIdentifierExpr();
            case LPAREN -> {
                advance();
                Expression expr = parseExpression();
                expect(TokenType.RPAREN);
                yield expr;
            }
            default -> throw error("Expected expression, got " + t.type());
        };
    }

    private Expression parseIdentifierExpr() {
        String name = advance().value();

        // Function call: name(...)
        if (peek().type() == TokenType.LPAREN) {
            return parseFunctionCall(name);
        }

        // Bracket access: cf['col']
        if (peek().type() == TokenType.LBRACKET) {
            advance();
            Expression key = parseExpression();
            expect(TokenType.RBRACKET);
            Expression obj = new ColumnRefExpr(null, name);
            return new BracketAccess(obj, key);
        }

        // Check for cf:qualifier pattern
        if (peek().type() == TokenType.COLON) {
            advance();
            String qualifier = expectIdentifier();
            return new ColumnRefExpr(name, qualifier);
        }

        // Map _key and _timestamp to special column refs
        if ("_key".equals(name)) {
            return new ColumnRefExpr(null, "rowkey");
        }

        // Plain identifier (e.g., "rowkey")
        return new ColumnRefExpr(null, name);
    }

    // ─── Function / CASE / CAST parsing ────────────────────────────────

    private Expression parseFunctionCall(String name) {
        expect(TokenType.LPAREN);
        List<Expression> args = new ArrayList<>();
        String funcName = name.toUpperCase();
        if (peek().type() != TokenType.RPAREN) {
            // Special case: COUNT(*)
            if (peek().type() == TokenType.STAR && "COUNT".equalsIgnoreCase(name)) {
                advance();
                args.add(new StarExpr());
            } else {
                // Optional DISTINCT inside aggregate functions — encode in name
                if (peek().type() == TokenType.DISTINCT) {
                    advance();
                    funcName = funcName + "_DISTINCT"; // e.g., COUNT_DISTINCT
                }
                args.add(parseExpression());
                while (peek().type() == TokenType.COMMA) {
                    advance();
                    args.add(parseExpression());
                }
            }
        }
        expect(TokenType.RPAREN);
        return new FunctionCall(funcName, args);
    }

    /**
     * Parse a function call where the function name is a keyword token (IF, COALESCE, NULLIF).
     */
    private Expression parseFunctionCallKeyword(String name) {
        advance(); // consume the keyword token
        expect(TokenType.LPAREN);
        List<Expression> args = new ArrayList<>();
        args.add(parseExpression());
        while (peek().type() == TokenType.COMMA) {
            advance();
            args.add(parseExpression());
        }
        expect(TokenType.RPAREN);
        return new FunctionCall(name, args);
    }

    private Expression parseCaseExpr() {
        expect(TokenType.CASE);
        List<WhenClause> whens = new ArrayList<>();
        while (peek().type() == TokenType.WHEN) {
            advance();
            Expression condition = parseExpression();
            expect(TokenType.THEN);
            Expression result = parseExpression();
            whens.add(new WhenClause(condition, result));
        }
        Expression elseExpr = null;
        if (peek().type() == TokenType.ELSE) {
            advance();
            elseExpr = parseExpression();
        }
        expect(TokenType.END);
        return new CaseExpr(whens, elseExpr);
    }

    private Expression parseCastExpr() {
        expect(TokenType.CAST);
        expect(TokenType.LPAREN);
        Expression expr = parseExpression();
        expect(TokenType.AS);
        String targetType = expectIdentifier();
        expect(TokenType.RPAREN);
        return new CastExpr(expr, targetType);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    /**
     * Parse table reference: "instance.table" or instance.table
     */
    private TableRef parseTableRef() {
        SqlToken t = peek();
        if (t.type() == TokenType.QUOTED_IDENTIFIER) {
            advance();
            String val = t.value();
            int dot = val.indexOf('.');
            if (dot <= 0) throw error("Table reference must be 'instance.table', got: " + val);
            return new TableRef(val.substring(0, dot), val.substring(dot + 1));
        }
        // Unquoted: instance.table
        String instance = expectIdentifier();
        expect(TokenType.DOT);
        String table = expectIdentifier();
        return new TableRef(instance, table);
    }

    /**
     * Parse column list: cf:col, cf:col, ...
     */
    private List<ColumnRef> parseColumnList() {
        List<ColumnRef> columns = new ArrayList<>();
        columns.add(parseColumnRef());
        while (peek().type() == TokenType.COMMA) {
            advance();
            columns.add(parseColumnRef());
        }
        return columns;
    }

    private ColumnRef parseColumnRef() {
        String name = expectIdentifier();
        if (peek().type() == TokenType.COLON) {
            advance();
            String qualifier = expectIdentifier();
            return new ColumnRef(name, qualifier);
        }
        // Bare identifier (e.g., "rowkey") — no family
        return new ColumnRef(null, name);
    }

    /**
     * Parse a column name for INSERT/UPDATE: "rowkey" or "cf:col"
     */
    private String parseColumnName() {
        String name = expectIdentifier();
        if (peek().type() == TokenType.COLON) {
            advance();
            String qualifier = expectIdentifier();
            return name + ":" + qualifier;
        }
        return name;
    }

    /**
     * Parse a value literal in VALUES clause.
     */
    private String parseValueLiteral() {
        SqlToken t = peek();
        if (t.type() == TokenType.STRING) { advance(); return t.value(); }
        if (t.type() == TokenType.NUMBER) { advance(); return t.value(); }
        if (t.type() == TokenType.NULL) { advance(); return null; }
        throw error("Expected value literal (string, number, or NULL)");
    }

    private SqlToken peek() {
        return pos < tokens.size() ? tokens.get(pos) : new SqlToken(TokenType.EOF, "", -1);
    }

    private SqlToken advance() {
        SqlToken t = peek();
        if (t.type() != TokenType.EOF) pos++;
        return t;
    }

    private SqlToken expect(TokenType type) {
        SqlToken t = peek();
        if (t.type() != type) {
            throw error("Expected " + type + ", got " + t.type() + " (" + t.value() + ")");
        }
        return advance();
    }

    private String expectIdentifier() {
        SqlToken t = peek();
        if (t.type() == TokenType.IDENTIFIER || t.type() == TokenType.QUOTED_IDENTIFIER) {
            advance();
            return t.value();
        }
        // Allow keywords used as identifiers (e.g., column named "table", "set", etc.)
        if (isKeyword(t.type())) {
            advance();
            return t.value();
        }
        throw error("Expected identifier, got " + t.type() + " (" + t.value() + ")");
    }

    private static final java.util.Set<TokenType> KEYWORD_TYPES = java.util.EnumSet.range(TokenType.SELECT, TokenType.NULLIF);

    private boolean isKeyword(TokenType type) {
        return KEYWORD_TYPES.contains(type);
    }

    private BigtableSqlException error(String message) {
        SqlToken t = peek();
        return new BigtableSqlException(message, t.position());
    }
}
