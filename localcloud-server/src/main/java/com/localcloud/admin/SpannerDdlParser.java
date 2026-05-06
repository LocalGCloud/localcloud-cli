package com.localcloud.admin;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.Index;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses Spanner CREATE TABLE DDL statements using JSqlParser.
 * Handles Spanner-specific constructs: INTERLEAVE IN PARENT, OPTIONS(),
 * generated columns (AS ... STORED), TOKENLIST types, and HIDDEN columns.
 */
public class SpannerDdlParser {

    private static final Logger logger = LoggerFactory.getLogger(SpannerDdlParser.class);

    private static final List<String> SKIP_KEYWORDS = List.of(
        "INTERLEAVE", "CONSTRAINT", "INDEX", "UNIQUE", "FOREIGN",
        "CHECK", "PRIMARY", "OPTIONS"
    );

    /**
     * Parse a Spanner CREATE TABLE DDL statement.
     * @param ddl the CREATE TABLE statement
     * @return map with "name" (table name) and "columns" (list of {name, type} maps), or null if parsing fails
     */
    public static Map<String, Object> parse(String ddl) {
        if (ddl == null || ddl.trim().isEmpty()) {
            return null;
        }

        String trimmed = ddl.trim();
        if (!trimmed.toUpperCase().startsWith("CREATE TABLE")) {
            return null;
        }

        try {
            CreateTable createTable = (CreateTable) CCJSqlParserUtil.parse(trimmed);
            Table table = createTable.getTable();
            if (table == null) {
                return null;
            }

            String tableName = table.getFullyQualifiedName();
            List<Map<String, String>> columns = new ArrayList<>();

            for (ColumnDefinition colDef : createTable.getColumnDefinitions()) {
                String colName = colDef.getColumnName();
                String colType = extractColumnType(colDef);

                if (colType == null || colType.equalsIgnoreCase("TOKENLIST")) {
                    continue;
                }

                String fullDef = colDef.toString().toUpperCase();
                if (fullDef.contains(" HIDDEN")) {
                    continue;
                }

                Map<String, String> col = new LinkedHashMap<>();
                col.put("name", colName);
                col.put("type", colType);
                columns.add(col);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", tableName);
            result.put("columns", columns);
            return result;
        } catch (Exception e) {
            logger.debug("JSqlParser failed to parse DDL, falling back to regex: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extract the base type from a column definition, handling Spanner-specific types.
     */
    private static String extractColumnType(ColumnDefinition colDef) {
        var colDataType = colDef.getColDataType();
        if (colDataType == null) {
            return null;
        }

        String dataType = colDataType.getDataType();
        List<String> args = colDataType.getArgumentsStringList();

        if (args != null && !args.isEmpty()) {
            return dataType + "(" + String.join(", ", args) + ")";
        }
        return dataType;
    }

    /**
     * Parse multiple CREATE TABLE statements from a single DDL input.
     * Supports semicolon-separated statements.
     */
    public static List<Map<String, Object>> parseAll(String ddl) {
        List<Map<String, Object>> results = new ArrayList<>();
        String[] statements = ddl.split(";");
        for (String stmt : statements) {
            String trimmed = stmt.trim();
            if (trimmed.isEmpty()) continue;
            Map<String, Object> parsed = parse(trimmed);
            if (parsed != null) {
                results.add(parsed);
            }
        }
        return results;
    }
}
