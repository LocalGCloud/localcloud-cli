package com.localcloud.admin.bigtablesql;

import java.util.List;
import java.util.Map;

/**
 * AST node hierarchy for the Bigtable SQL dialect.
 */
public sealed interface SqlAstNode {

    // ─── Table reference: "instance.table" ───
    record TableRef(String instance, String table) implements SqlAstNode {}

    // ─── Column reference: cf:qualifier ───
    record ColumnRef(String family, String qualifier) implements SqlAstNode {}

    // ─── Column family definition (for CREATE TABLE) ───
    record ColumnFamilyDef(String name, int maxVersions) implements SqlAstNode {}

    // ─── Family modification (for ALTER TABLE) ───
    record FamilyModification(String action, String familyName, int maxVersions) implements SqlAstNode {}

    // ─── Select support records ───

    record SelectColumn(Expression expr, String alias) implements SqlAstNode {}
    record OrderByClause(Expression expr, boolean ascending) implements SqlAstNode {}

    // ─── DML Statements ───

    record SelectStatement(
            boolean distinct,
            TableRef table,
            List<SelectColumn> columns,  // null = SELECT *
            Expression where,            // null = no WHERE
            List<Expression> groupBy,    // null = no GROUP BY
            Expression having,           // null = no HAVING
            List<OrderByClause> orderBy, // null = no ORDER BY
            int limit,                   // -1 = no LIMIT
            int offset                   // -1 = no OFFSET
    ) implements SqlAstNode {}

    record InsertStatement(
            TableRef table,
            List<String> columnNames, // ["rowkey", "cf1:name", "cf1:email"]
            List<String> values       // matching values
    ) implements SqlAstNode {}

    record UpdateStatement(
            TableRef table,
            Map<String, Expression> assignments, // "cf1:name" → StringLiteral("val")
            Expression where
    ) implements SqlAstNode {}

    record DeleteStatement(
            TableRef table,
            Expression where
    ) implements SqlAstNode {}

    // ─── DDL Statements ───

    record CreateTableStatement(
            TableRef table,
            List<ColumnFamilyDef> families
    ) implements SqlAstNode {}

    record DropTableStatement(TableRef table) implements SqlAstNode {}

    record AlterTableStatement(
            TableRef table,
            List<FamilyModification> modifications
    ) implements SqlAstNode {}

    record ShowTablesStatement(String instance) implements SqlAstNode {}

    record DescribeTableStatement(TableRef table) implements SqlAstNode {}

    // ─── Expressions (for WHERE clauses, SET assignments) ───

    sealed interface Expression extends SqlAstNode {}

    record StringLiteral(String value) implements Expression {}
    record NumberLiteral(long value) implements Expression {}
    record NullLiteral() implements Expression {}
    record ColumnRefExpr(String family, String qualifier) implements Expression {}
    record BinaryOp(String operator, Expression left, Expression right) implements Expression {}
    record BetweenExpr(Expression expr, Expression low, Expression high) implements Expression {}
    record LikeExpr(Expression expr, String pattern) implements Expression {}
    record InExpr(Expression expr, List<Expression> values) implements Expression {}
    record BooleanLiteral(boolean value) implements Expression {}
    record FloatLiteral(double value) implements Expression {}
    record FunctionCall(String name, List<Expression> args) implements Expression {}
    record CaseExpr(List<WhenClause> whens, Expression elseExpr) implements Expression {}
    record WhenClause(Expression condition, Expression result) {}
    record CastExpr(Expression expr, String targetType) implements Expression {}
    record IsNullExpr(Expression expr, boolean negated) implements Expression {}
    record BracketAccess(Expression object, Expression key) implements Expression {}
    record StarExpr() implements Expression {}
}
