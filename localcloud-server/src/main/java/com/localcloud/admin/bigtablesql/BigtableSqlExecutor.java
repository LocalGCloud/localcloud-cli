package com.localcloud.admin.bigtablesql;

import com.localcloud.admin.BigtableGrpcClient;
import com.localcloud.admin.bigtablesql.SqlAstNode.*;

import com.google.bigtable.v2.RowFilter;
import com.google.bigtable.v2.RowRange;
import com.google.bigtable.v2.RowSet;
import com.google.protobuf.ByteString;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Executes parsed Bigtable SQL statements by translating them to gRPC calls.
 */
public final class BigtableSqlExecutor {

    private final BigtableGrpcClient client;
    private final String projectId;

    public BigtableSqlExecutor(BigtableGrpcClient client, String projectId) {
        this.client = client;
        this.projectId = projectId;
    }

    /**
     * Execute a parsed SQL statement and return the result.
     */
    public QueryResult execute(SqlAstNode statement) {
        return switch (statement) {
            case SelectStatement sel -> executeSelect(sel);
            case InsertStatement ins -> executeInsert(ins);
            case UpdateStatement upd -> executeUpdate(upd);
            case DeleteStatement del -> executeDelete(del);
            case CreateTableStatement cts -> executeCreateTable(cts);
            case DropTableStatement dts -> executeDropTable(dts);
            case AlterTableStatement ats -> executeAlterTable(ats);
            case ShowTablesStatement sts -> executeShowTables(sts);
            case DescribeTableStatement dts -> executeDescribe(dts);
            default -> throw new BigtableSqlException("Unsupported statement type: " + statement.getClass().getSimpleName());
        };
    }

    // ─── SELECT ──────────────────────────────────────────────────────────

    private QueryResult executeSelect(SelectStatement sel) {
        String inst = sel.table().instance();
        String tbl = sel.table().table();

        // For aggregation queries, fetch more rows; for simple queries use limit
        boolean hasAggregation = sel.groupBy() != null || containsAggregate(sel);
        int fetchLimit = hasAggregation ? 10000 : (sel.limit() > 0 ? sel.limit() + (sel.offset() > 0 ? sel.offset() : 0) : 100);

        // ── Stage 1: Build RowSet + RowFilter and fetch from emulator ──
        RowSet rowSet = compileRowSet(sel.where());
        List<ColumnRef> columnRefs = extractColumnRefs(sel.columns());
        RowFilter filter = compileFilter(columnRefs, sel.where());
        List<Map<String, Object>> rawRows = client.readRowsFiltered(projectId, inst, tbl, rowSet, filter, fetchLimit);

        // ── Stage 2: Flatten cells into tabular rows ──
        List<Map<String, Object>> flatRows = flattenRows(rawRows);

        // ── Stage 3: Client-side WHERE filter (non-pushable conditions) ──
        if (sel.where() != null && !isFullyPushable(sel.where())) {
            flatRows = flatRows.stream()
                    .filter(row -> {
                        Object result = SqlAggregator.evaluateExpr(sel.where(), row);
                        return result instanceof Boolean b ? b : result != null;
                    })
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }

        // ── Stage 4: GROUP BY + aggregate or evaluate/project SELECT expressions ──
        if (hasAggregation) {
            flatRows = SqlAggregator.groupAndAggregate(flatRows, sel.groupBy(), sel.columns(), sel.having());
        } else if (sel.columns() != null) {
            // Always project columns when SELECT specifies them (not SELECT *)
            flatRows = evaluateSelectExpressions(flatRows, sel.columns());
        }

        // ── Stage 5: ORDER BY ──
        if (sel.orderBy() != null && !sel.orderBy().isEmpty()) {
            flatRows.sort((a, b) -> {
                for (OrderByClause clause : sel.orderBy()) {
                    Object va = SqlAggregator.evaluateExpr(clause.expr(), a);
                    Object vb = SqlAggregator.evaluateExpr(clause.expr(), b);
                    Integer cmp = SqlTypes.compare(va, vb);
                    if (cmp == null) continue;
                    if (cmp != 0) return clause.ascending() ? cmp : -cmp;
                }
                return 0;
            });
        }

        // ── Stage 6: DISTINCT ──
        if (sel.distinct()) {
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            List<Map<String, Object>> deduped = new ArrayList<>();
            for (var row : flatRows) {
                String key = row.values().toString();
                if (seen.add(key)) deduped.add(row);
            }
            flatRows = deduped;
        }

        // ── Stage 7: OFFSET + LIMIT ──
        if (sel.offset() > 0 && sel.offset() < flatRows.size()) {
            flatRows = new ArrayList<>(flatRows.subList(sel.offset(), flatRows.size()));
        }
        if (sel.limit() > 0 && flatRows.size() > sel.limit()) {
            flatRows = new ArrayList<>(flatRows.subList(0, sel.limit()));
        }

        // ── Stage 8: Build response columns ──
        List<Map<String, String>> columns = buildResultColumns(flatRows, sel.columns());
        return new QueryResult(columns, flatRows, flatRows.size());
    }

    /** Flatten raw gRPC rows (with nested cells map) into flat column maps. */
    private List<Map<String, Object>> flattenRows(List<Map<String, Object>> rawRows) {
        List<Map<String, Object>> flatRows = new ArrayList<>();
        for (Map<String, Object> row : rawRows) {
            Map<String, Object> flat = new LinkedHashMap<>();
            flat.put("rowKey", row.get("rowKey"));
            // _key alias for GoogleSQL compatibility
            flat.put("_key", row.get("rowKey"));
            @SuppressWarnings("unchecked")
            Map<String, Object> cells = (Map<String, Object>) row.get("cells");
            if (cells != null) flat.putAll(cells);
            flatRows.add(flat);
        }
        return flatRows;
    }

    /** Evaluate SELECT expressions (functions, CAST, aliases) per row. */
    private List<Map<String, Object>> evaluateSelectExpressions(List<Map<String, Object>> rows,
                                                                 List<SelectColumn> selectColumns) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var row : rows) {
            Map<String, Object> output = new LinkedHashMap<>();
            for (SelectColumn sc : selectColumns) {
                String alias = sc.alias() != null ? sc.alias() : SqlAggregator.exprToAlias(sc.expr());
                Object value = SqlAggregator.evaluateExpr(sc.expr(), row);
                output.put(alias, value);
            }
            result.add(output);
        }
        return result;
    }

    /** Build response column metadata from result rows. */
    private List<Map<String, String>> buildResultColumns(List<Map<String, Object>> rows,
                                                          List<SelectColumn> selectColumns) {
        LinkedHashSet<String> colNames = new LinkedHashSet<>();
        if (selectColumns != null) {
            for (SelectColumn sc : selectColumns) {
                colNames.add(sc.alias() != null ? sc.alias() : SqlAggregator.exprToAlias(sc.expr()));
            }
        }
        // Also include any columns present in actual rows
        for (var row : rows) {
            colNames.addAll(row.keySet());
        }
        // Remove internal _key alias if rowKey already present
        if (colNames.contains("rowKey") && colNames.contains("_key")) {
            colNames.remove("_key");
        }
        return colNames.stream()
                .map(c -> Map.of("name", c, "type", "STRING"))
                .toList();
    }

    /** Check if SELECT contains any non-simple column references. */
    private boolean hasExpressionColumns(List<SelectColumn> columns) {
        return columns.stream().anyMatch(sc ->
                !(sc.expr() instanceof ColumnRefExpr) && !(sc.expr() instanceof StarExpr));
    }

    /** Check if SELECT or its columns contain aggregate functions. */
    private boolean containsAggregate(SelectStatement sel) {
        if (sel.columns() == null) return false;
        return sel.columns().stream().anyMatch(sc -> containsAggregateExpr(sc.expr()));
    }

    private boolean containsAggregateExpr(Expression expr) {
        if (expr instanceof FunctionCall fc) return SqlFunctions.isAggregate(fc.name());
        if (expr instanceof CastExpr ce) return containsAggregateExpr(ce.expr());
        if (expr instanceof BinaryOp op) return containsAggregateExpr(op.left()) || containsAggregateExpr(op.right());
        return false;
    }

    /** Check if WHERE clause is fully pushable to gRPC (rowkey-only conditions). */
    private boolean isFullyPushable(Expression where) {
        if (where == null) return true;
        if (where instanceof BinaryOp op && ("=".equals(op.operator()) || "AND".equals(op.operator()))) {
            if (isRowKeyRef(op.left())) return true;
            if ("AND".equals(op.operator())) return isFullyPushable(op.left()) && isFullyPushable(op.right());
        }
        if (where instanceof BetweenExpr be) return isRowKeyRef(be.expr());
        if (where instanceof LikeExpr le) return isRowKeyRef(le.expr()) && le.pattern().endsWith("%") && !le.pattern().contains("_") && le.pattern().indexOf('%') == le.pattern().length() - 1;
        if (where instanceof InExpr ie) return isRowKeyRef(ie.expr());
        return false;
    }

    /**
     * Extract ColumnRef projections from SelectColumn list for backward-compatible filtering.
     * Returns null if all columns are selected (SELECT *).
     */
    private List<ColumnRef> extractColumnRefs(List<SelectColumn> selectColumns) {
        if (selectColumns == null) return null;
        List<ColumnRef> refs = new ArrayList<>();
        for (SelectColumn sc : selectColumns) {
            if (sc.expr() instanceof ColumnRefExpr cre) {
                refs.add(new ColumnRef(cre.family(), cre.qualifier()));
            }
            // Non-column expressions (functions, etc.) are not pushed down to Bigtable filtering
        }
        return refs.isEmpty() ? null : refs;
    }

    // ─── INSERT ──────────────────────────────────────────────────────────

    private QueryResult executeInsert(InsertStatement ins) {
        String inst = ins.table().instance();
        String tbl = ins.table().table();

        String rowKey = null;
        Map<String, Object> cells = new LinkedHashMap<>();
        for (int i = 0; i < ins.columnNames().size(); i++) {
            String col = ins.columnNames().get(i);
            String val = ins.values().get(i);
            if ("rowkey".equalsIgnoreCase(col)) {
                rowKey = val;
            } else {
                cells.put(col, val);
            }
        }
        if (rowKey == null) {
            throw new BigtableSqlException("INSERT requires a 'rowkey' column");
        }

        client.mutateRow(projectId, inst, tbl, rowKey, cells);
        return QueryResult.message("Inserted 1 row into " + inst + "." + tbl);
    }

    // ─── UPDATE ──────────────────────────────────────────────────────────

    private QueryResult executeUpdate(UpdateStatement upd) {
        String inst = upd.table().instance();
        String tbl = upd.table().table();
        String rowKey = extractRowKey(upd.where());
        if (rowKey == null) {
            throw new BigtableSqlException("UPDATE requires WHERE rowkey = 'value'");
        }

        // Check for increment pattern: cf:col = cf:col + N
        for (Map.Entry<String, Expression> entry : upd.assignments().entrySet()) {
            if (entry.getValue() instanceof BinaryOp op && "+".equals(op.operator())) {
                if (op.left() instanceof ColumnRefExpr ref && op.right() instanceof NumberLiteral num) {
                    String family = ref.family() != null ? ref.family() : entry.getKey().split(":")[0];
                    String qualifier = ref.qualifier();
                    client.readModifyWriteRow(projectId, inst, tbl, rowKey,
                            family, qualifier, num.value());
                    return QueryResult.message("Incremented " + entry.getKey() + " by " + num.value());
                }
            }
        }

        // Regular SET: build cells map
        Map<String, Object> cells = new LinkedHashMap<>();
        for (Map.Entry<String, Expression> entry : upd.assignments().entrySet()) {
            cells.put(entry.getKey(), evaluateToString(entry.getValue()));
        }
        client.mutateRow(projectId, inst, tbl, rowKey, cells);
        return QueryResult.message("Updated " + cells.size() + " column(s) in row '" + rowKey + "'");
    }

    // ─── DELETE ──────────────────────────────────────────────────────────

    private QueryResult executeDelete(DeleteStatement del) {
        String inst = del.table().instance();
        String tbl = del.table().table();

        // DELETE WHERE rowkey BETWEEN 'a' AND 'z' → dropRowRange
        if (del.where() instanceof BetweenExpr between) {
            Expression subject = between.expr();
            if (subject instanceof ColumnRefExpr ref && "rowkey".equalsIgnoreCase(ref.qualifier())) {
                String prefix = evaluateToString(between.low());
                client.dropRowRange(projectId, inst, tbl, prefix);
                return QueryResult.message("Dropped row range with prefix '" + prefix + "'");
            }
        }

        // DELETE WHERE rowkey = 'key'
        String rowKey = extractRowKey(del.where());
        if (rowKey == null) {
            throw new BigtableSqlException("DELETE requires WHERE rowkey = 'value' or WHERE rowkey BETWEEN ...");
        }
        client.deleteRow(projectId, inst, tbl, rowKey);
        return QueryResult.message("Deleted row '" + rowKey + "' from " + inst + "." + tbl);
    }

    // ─── CREATE TABLE ────────────────────────────────────────────────────

    private QueryResult executeCreateTable(CreateTableStatement cts) {
        String inst = cts.table().instance();
        String tbl = cts.table().table();
        List<String> families = cts.families().stream()
                .map(ColumnFamilyDef::name)
                .toList();
        client.ensureTable(projectId, inst, tbl, families);
        return QueryResult.message("Created table " + inst + "." + tbl + " with families: " + String.join(", ", families));
    }

    // ─── DROP TABLE ──────────────────────────────────────────────────────

    private QueryResult executeDropTable(DropTableStatement dts) {
        client.deleteTable(projectId, dts.table().instance(), dts.table().table());
        return QueryResult.message("Dropped table " + dts.table().instance() + "." + dts.table().table());
    }

    // ─── ALTER TABLE ─────────────────────────────────────────────────────

    private QueryResult executeAlterTable(AlterTableStatement ats) {
        String inst = ats.table().instance();
        String tbl = ats.table().table();
        List<String> addFamilies = new ArrayList<>();
        List<String> dropFamilies = new ArrayList<>();
        for (FamilyModification mod : ats.modifications()) {
            if ("ADD".equals(mod.action())) addFamilies.add(mod.familyName());
            else if ("DROP".equals(mod.action())) dropFamilies.add(mod.familyName());
        }
        client.modifyColumnFamilies(projectId, inst, tbl,
                addFamilies.isEmpty() ? null : addFamilies,
                dropFamilies.isEmpty() ? null : dropFamilies);
        return QueryResult.message("Altered table " + inst + "." + tbl);
    }

    // ─── SHOW TABLES ─────────────────────────────────────────────────────

    private QueryResult executeShowTables(ShowTablesStatement sts) {
        var instances = client.listInstancesWithDetails(projectId);
        List<Map<String, String>> columns = List.of(
                Map.of("name", "instance", "type", "STRING"),
                Map.of("name", "table", "type", "STRING"),
                Map.of("name", "column_families", "type", "STRING"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var inst : instances) {
            String instanceId = (String) inst.get("id");
            if (sts.instance() != null && !sts.instance().equals(instanceId)) continue;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tables = (List<Map<String, Object>>) inst.get("tables");
            if (tables != null) {
                for (var tbl : tables) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("instance", instanceId);
                    row.put("table", tbl.get("id"));
                    @SuppressWarnings("unchecked")
                    List<String> cfs = (List<String>) tbl.get("columnFamilies");
                    row.put("column_families", cfs != null ? String.join(", ", cfs) : "");
                    rows.add(row);
                }
            }
        }
        return new QueryResult(columns, rows, rows.size());
    }

    // ─── DESCRIBE ────────────────────────────────────────────────────────

    private QueryResult executeDescribe(DescribeTableStatement dts) {
        List<String> families = client.getColumnFamilies(projectId,
                dts.table().instance(), dts.table().table());
        List<Map<String, String>> columns = List.of(
                Map.of("name", "column_family", "type", "STRING"));
        List<Map<String, Object>> rows = families.stream()
                .map(f -> (Map<String, Object>) Map.<String, Object>of("column_family", f))
                .toList();
        return new QueryResult(columns, rows, rows.size());
    }

    // ─── WHERE clause → RowSet/RowFilter compilation ─────────────────────

    /**
     * Compile WHERE clause into RowSet (key-based filtering).
     */
    private RowSet compileRowSet(Expression where) {
        if (where == null) return null;

        // rowkey = 'value' → single key
        if (where instanceof BinaryOp op && "=".equals(op.operator())) {
            if (isRowKeyRef(op.left())) {
                String key = evaluateToString(op.right());
                return RowSet.newBuilder()
                        .addRowKeys(ByteString.copyFromUtf8(key))
                        .build();
            }
        }

        // rowkey BETWEEN 'a' AND 'z' → range
        if (where instanceof BetweenExpr between && isRowKeyRef(between.expr())) {
            String start = evaluateToString(between.low());
            String end = evaluateToString(between.high());
            return RowSet.newBuilder()
                    .addRowRanges(RowRange.newBuilder()
                            .setStartKeyClosed(ByteString.copyFromUtf8(start))
                            .setEndKeyClosed(ByteString.copyFromUtf8(end))
                            .build())
                    .build();
        }

        // rowkey LIKE 'prefix%' → prefix range
        if (where instanceof LikeExpr like && isRowKeyRef(like.expr())) {
            String pattern = like.pattern();
            if (pattern.endsWith("%")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                byte[] prefixBytes = prefix.getBytes();
                byte[] endBytes = incrementPrefix(prefixBytes);
                RowRange.Builder range = RowRange.newBuilder()
                        .setStartKeyClosed(ByteString.copyFromUtf8(prefix));
                if (endBytes != null) {
                    range.setEndKeyOpen(ByteString.copyFrom(endBytes));
                }
                // If endBytes is null (all 0xFF), leave end key open for unbounded range
                return RowSet.newBuilder()
                        .addRowRanges(range.build())
                        .build();
            }
        }

        // rowkey IN ('a', 'b', 'c') → multiple keys
        if (where instanceof InExpr in && isRowKeyRef(in.expr())) {
            RowSet.Builder builder = RowSet.newBuilder();
            for (Expression val : in.values()) {
                builder.addRowKeys(ByteString.copyFromUtf8(evaluateToString(val)));
            }
            return builder.build();
        }

        return null;
    }

    /**
     * Compile column projection and non-rowkey WHERE filters into RowFilter.
     */
    private RowFilter compileFilter(List<ColumnRef> columns, Expression where) {
        List<RowFilter> filters = new ArrayList<>();

        // Column family filter from SELECT column list
        if (columns != null && !columns.isEmpty()) {
            LinkedHashSet<String> families = new LinkedHashSet<>();
            for (ColumnRef ref : columns) {
                if (ref.family() != null) families.add(ref.family());
            }
            if (!families.isEmpty() && families.size() < 5) {
                // Interleave family filters
                if (families.size() == 1) {
                    filters.add(RowFilter.newBuilder()
                            .setFamilyNameRegexFilter(families.iterator().next())
                            .build());
                } else {
                    RowFilter.Interleave.Builder interleave = RowFilter.Interleave.newBuilder();
                    for (String family : families) {
                        interleave.addFilters(RowFilter.newBuilder()
                                .setFamilyNameRegexFilter(family)
                                .build());
                    }
                    filters.add(RowFilter.newBuilder().setInterleave(interleave).build());
                }
            }
        }

        // Cells per column limit (always return latest version)
        filters.add(RowFilter.newBuilder().setCellsPerColumnLimitFilter(1).build());

        if (filters.size() == 1) return filters.get(0);
        RowFilter.Chain.Builder chain = RowFilter.Chain.newBuilder();
        filters.forEach(chain::addFilters);
        return RowFilter.newBuilder().setChain(chain).build();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    /**
     * Increment the last byte of a prefix for exclusive upper bound in range scans.
     * Handles 0xFF overflow by trimming trailing 0xFF bytes.
     * Returns null if all bytes are 0xFF (open-ended range).
     */
    private static byte[] incrementPrefix(byte[] prefix) {
        byte[] end = prefix.clone();
        for (int i = end.length - 1; i >= 0; i--) {
            if ((end[i] & 0xFF) < 0xFF) {
                end[i]++;
                return java.util.Arrays.copyOf(end, i + 1);
            }
        }
        return null; // all 0xFF — open-ended range
    }

    private boolean isRowKeyRef(Expression expr) {
        return expr instanceof ColumnRefExpr ref &&
                "rowkey".equalsIgnoreCase(ref.qualifier()) && ref.family() == null;
    }

    private String extractRowKey(Expression where) {
        if (where instanceof BinaryOp op && "=".equals(op.operator()) && isRowKeyRef(op.left())) {
            return evaluateToString(op.right());
        }
        return null;
    }

    private String evaluateToString(Expression expr) {
        return switch (expr) {
            case StringLiteral s -> s.value();
            case NumberLiteral n -> String.valueOf(n.value());
            case FloatLiteral f -> String.valueOf(f.value());
            case BooleanLiteral b -> String.valueOf(b.value());
            case NullLiteral ignored -> null;
            default -> throw new BigtableSqlException("Cannot evaluate expression to string: " + expr);
        };
    }

    /**
     * Response container for SQL query results.
     */
    public record QueryResult(
            List<Map<String, String>> columns,
            List<Map<String, Object>> rows,
            int rowCount
    ) {
        static QueryResult message(String msg) {
            return new QueryResult(
                    List.of(Map.of("name", "result", "type", "STRING")),
                    List.of(Map.of("result", msg)),
                    1);
        }
    }
}
