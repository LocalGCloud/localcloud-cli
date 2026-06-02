package com.localcloud.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.google.bigtable.admin.v2.BigtableInstanceAdminGrpc;
import com.google.bigtable.admin.v2.BigtableTableAdminGrpc;
import com.google.bigtable.admin.v2.Cluster;
import com.google.bigtable.admin.v2.ColumnFamily;
import com.google.bigtable.admin.v2.CreateInstanceRequest;
import com.google.bigtable.admin.v2.CreateTableRequest;
import com.google.bigtable.admin.v2.DeleteInstanceRequest;
import com.google.bigtable.admin.v2.DeleteTableRequest;
import com.google.bigtable.admin.v2.GetInstanceRequest;
import com.google.bigtable.admin.v2.GetTableRequest;
import com.google.bigtable.admin.v2.Instance;
import com.google.bigtable.admin.v2.ListInstancesRequest;
import com.google.bigtable.admin.v2.ListTablesRequest;
import com.google.bigtable.admin.v2.StorageType;
import com.google.bigtable.admin.v2.Table;
import com.google.bigtable.admin.v2.DropRowRangeRequest;
import com.google.bigtable.admin.v2.ModifyColumnFamiliesRequest;
import com.google.bigtable.v2.BigtableGrpc;
import com.google.bigtable.v2.MutateRowRequest;
import com.google.bigtable.v2.Mutation;
import com.google.bigtable.v2.ReadModifyWriteRowRequest;
import com.google.bigtable.v2.ReadModifyWriteRule;
import com.google.bigtable.v2.ReadRowsRequest;
import com.google.bigtable.v2.ReadRowsResponse;
import com.google.bigtable.v2.RowFilter;
import com.google.bigtable.v2.RowRange;
import com.google.bigtable.v2.RowSet;
import com.google.protobuf.ByteString;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

public final class BigtableGrpcClient implements AutoCloseable {
    private static final String DEFAULT_CLUSTER = "local-cluster";

    private final ManagedChannel channel;
    private final BigtableGrpc.BigtableBlockingStub data;
    private final BigtableTableAdminGrpc.BigtableTableAdminBlockingStub tableAdmin;
    private final BigtableInstanceAdminGrpc.BigtableInstanceAdminBlockingStub instanceAdmin;

    public BigtableGrpcClient(int port) {
        this.channel = ManagedChannelBuilder.forAddress("localhost", port)
                .usePlaintext()
                .build();
        this.data = BigtableGrpc.newBlockingStub(channel);
        this.tableAdmin = BigtableTableAdminGrpc.newBlockingStub(channel);
        this.instanceAdmin = BigtableInstanceAdminGrpc.newBlockingStub(channel);
    }

    /**
     * List tables for a single instance without scanning the entire cluster.
     */
    public List<Map<String, Object>> listTablesForInstance(String projectId, String instanceId) {
        List<Map<String, Object>> tables = new ArrayList<>();
        var response = tableAdmin.listTables(ListTablesRequest.newBuilder()
                .setParent(instanceName(projectId, instanceId))
                .build());
        for (Table table : response.getTablesList()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("instance", instanceId);
            item.put("table", tail(table.getName(), "/tables/"));
            item.put("granularity", table.getGranularity().name());
            tables.add(item);
        }
        return tables;
    }

    public List<Map<String, Object>> listTables(String projectId) {
        List<Map<String, Object>> tables = new ArrayList<>();
        var instances = instanceAdmin.listInstances(ListInstancesRequest.newBuilder()
                .setParent(project(projectId))
                .build());
        for (Instance instance : instances.getInstancesList()) {
            String instanceId = tail(instance.getName(), "/instances/");
            var response = tableAdmin.listTables(ListTablesRequest.newBuilder()
                    .setParent(instance.getName())
                    .build());
            for (Table table : response.getTablesList()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("instance", instanceId);
                item.put("table", tail(table.getName(), "/tables/"));
                tables.add(item);
            }
        }
        return tables;
    }

    /**
     * Returns instances with their tables and column families for the data explorer
     * and schema explorer. Shape: [{id, type:"instance", tables: [{id, name, columnFamilies: [...]}]}]
     */
    public List<Map<String, Object>> listInstancesWithDetails(String projectId) {
        List<Map<String, Object>> result = new ArrayList<>();
        var instances = instanceAdmin.listInstances(ListInstancesRequest.newBuilder()
                .setParent(project(projectId))
                .build());
        for (Instance instance : instances.getInstancesList()) {
            String instanceId = tail(instance.getName(), "/instances/");
            Map<String, Object> instMap = new LinkedHashMap<>();
            instMap.put("id", instanceId);
            instMap.put("type", "instance");

            List<Map<String, Object>> tablesList = new ArrayList<>();
            var tables = tableAdmin.listTables(ListTablesRequest.newBuilder()
                    .setParent(instance.getName())
                    .build());
            for (Table table : tables.getTablesList()) {
                String tableId = tail(table.getName(), "/tables/");
                Map<String, Object> tableMap = new LinkedHashMap<>();
                tableMap.put("id", tableId);
                tableMap.put("name", tableId);
                // Get column families via schema view
                List<String> columnFamilies = new ArrayList<>();
                try {
                    Table fullTable = tableAdmin.getTable(GetTableRequest.newBuilder()
                            .setName(table.getName())
                            .setView(Table.View.SCHEMA_VIEW)
                            .build());
                    columnFamilies.addAll(fullTable.getColumnFamiliesMap().keySet());
                } catch (Exception e) {
                    columnFamilies.addAll(table.getColumnFamiliesMap().keySet());
                }
                tableMap.put("columnFamilies", columnFamilies);
                tablesList.add(tableMap);
            }
            instMap.put("tables", tablesList);
            result.add(instMap);
        }
        return result;
    }

    public List<Map<String, Object>> readRows(String projectId, String instanceId, String tableId, int limit) {
        String tableName = tableName(projectId, instanceId, tableId);
        var request = ReadRowsRequest.newBuilder()
                .setTableName(tableName)
                .setRowsLimit(limit)
                .build();
        return parseChunkedResponse(data.readRows(request));
    }

    public void ensureInstance(String projectId, String instanceId) {
        ensureInstance(projectId, instanceId, instanceId, "PRODUCTION");
    }

    public void ensureInstance(String projectId, String instanceId, String displayName, String instanceType) {
        String name = instanceName(projectId, instanceId);
        try {
            instanceAdmin.getInstance(GetInstanceRequest.newBuilder().setName(name).build());
            return;
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() != Status.Code.NOT_FOUND) {
                throw e;
            }
        }
        Instance.Type type;
        try {
            type = Instance.Type.valueOf(instanceType != null ? instanceType : "PRODUCTION");
        } catch (IllegalArgumentException e) {
            type = Instance.Type.PRODUCTION;
        }
        instanceAdmin.createInstance(CreateInstanceRequest.newBuilder()
                .setParent(project(projectId))
                .setInstanceId(instanceId)
                .setInstance(Instance.newBuilder()
                        .setDisplayName(displayName != null ? displayName : instanceId)
                        .setType(type)
                        .build())
                .putClusters(DEFAULT_CLUSTER, Cluster.newBuilder()
                        .setLocation(project(projectId) + "/locations/local")
                        .setServeNodes(1)
                        .setDefaultStorageType(StorageType.SSD)
                        .build())
                .build());
    }

    public void ensureTable(String projectId, String instanceId, String tableId, List<String> families) {
        ensureTable(projectId, instanceId, tableId, families, "MILLIS");
    }

    public void ensureTable(String projectId, String instanceId, String tableId, List<String> families, String granularity) {
        ensureInstance(projectId, instanceId);
        String name = tableName(projectId, instanceId, tableId);
        try {
            tableAdmin.getTable(GetTableRequest.newBuilder().setName(name).build());
            return;
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() != Status.Code.NOT_FOUND) {
                throw e;
            }
        }
        Table.Builder table = Table.newBuilder();
        if ("MILLIS".equalsIgnoreCase(granularity)) {
            table.setGranularity(Table.TimestampGranularity.MILLIS);
        }
        for (String family : families) {
            table.putColumnFamilies(family, ColumnFamily.newBuilder().build());
        }
        tableAdmin.createTable(CreateTableRequest.newBuilder()
                .setParent(instanceName(projectId, instanceId))
                .setTableId(tableId)
                .setTable(table)
                .build());
    }

    public void mutateRow(String projectId, String instanceId, String tableId, String rowKey, Map<String, Object> cells) {
        List<String> families = families(cells);
        if (families.isEmpty()) {
            families = List.of("cf1");
        }
        ensureTable(projectId, instanceId, tableId, families);
        MutateRowRequest.Builder request = MutateRowRequest.newBuilder()
                .setTableName(tableName(projectId, instanceId, tableId))
                .setRowKey(ByteString.copyFromUtf8(rowKey));
        for (Map.Entry<String, Object> entry : flattenCells(cells).entrySet()) {
            String key = entry.getKey();
            int idx = key.indexOf(':');
            String family = idx > 0 ? key.substring(0, idx) : "cf1";
            String qualifier = idx > 0 ? key.substring(idx + 1) : key;
            request.addMutations(Mutation.newBuilder()
                    .setSetCell(Mutation.SetCell.newBuilder()
                            .setFamilyName(family)
                            .setColumnQualifier(ByteString.copyFromUtf8(qualifier))
                            .setTimestampMicros(-1)
                            .setValue(ByteString.copyFromUtf8(String.valueOf(entry.getValue())))
                            .build())
                    .build());
        }
        data.mutateRow(request.build());
    }

    public void deleteRow(String projectId, String instanceId, String tableId, String rowKey) {
        data.mutateRow(MutateRowRequest.newBuilder()
                .setTableName(tableName(projectId, instanceId, tableId))
                .setRowKey(ByteString.copyFromUtf8(rowKey))
                .addMutations(Mutation.newBuilder()
                        .setDeleteFromRow(Mutation.DeleteFromRow.newBuilder().build())
                        .build())
                .build());
    }

    /**
     * Get a single instance by ID. Returns null if not found.
     */
    public Map<String, Object> getInstance(String projectId, String instanceId) {
        try {
            Instance instance = instanceAdmin.getInstance(GetInstanceRequest.newBuilder()
                    .setName(instanceName(projectId, instanceId))
                    .build());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("instanceId", instanceId);
            result.put("displayName", instance.getDisplayName());
            result.put("instanceType", instance.getType().name());
            result.put("state", instance.getState().name());
            return result;
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return null;
            }
            throw e;
        }
    }

    /**
     * Delete a single instance and all its tables.
     */
    public void deleteInstance(String projectId, String instanceId) {
        instanceAdmin.deleteInstance(DeleteInstanceRequest.newBuilder()
                .setName(instanceName(projectId, instanceId))
                .build());
    }

    public int resetProject(String projectId) {
        int count = 0;
        var instances = instanceAdmin.listInstances(ListInstancesRequest.newBuilder()
                .setParent(project(projectId))
                .build());
        for (Instance instance : instances.getInstancesList()) {
            instanceAdmin.deleteInstance(DeleteInstanceRequest.newBuilder()
                    .setName(instance.getName())
                    .build());
            count++;
        }
        return count;
    }

    public void deleteTable(String projectId, String instanceId, String tableId) {
        tableAdmin.deleteTable(DeleteTableRequest.newBuilder()
                .setName(tableName(projectId, instanceId, tableId))
                .build());
    }

    /**
     * Read rows with optional RowSet (keys/ranges) and RowFilter.
     */
    public List<Map<String, Object>> readRowsFiltered(String projectId, String instanceId, String tableId,
                                                RowSet rowSet, RowFilter filter, int limit) {
        ReadRowsRequest.Builder request = ReadRowsRequest.newBuilder()
                .setTableName(tableName(projectId, instanceId, tableId));
        if (rowSet != null) request.setRows(rowSet);
        if (filter != null) request.setFilter(filter);
        if (limit > 0) request.setRowsLimit(limit);
        return parseChunkedResponse(data.readRows(request.build()));
    }

    /**
     * Parse a chunked ReadRows response stream into a list of row maps.
     * Each row map contains "rowKey" (String) and "cells" (Map of family:qualifier to value).
     */
    private List<Map<String, Object>> parseChunkedResponse(java.util.Iterator<ReadRowsResponse> iterator) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String currentRowKey = null;
        String currentFamily = null;
        ByteString currentQualifier = ByteString.EMPTY;
        Map<String, Object> currentCells = new LinkedHashMap<>();

        while (iterator.hasNext()) {
            ReadRowsResponse response = iterator.next();
            for (ReadRowsResponse.CellChunk chunk : response.getChunksList()) {
                if (!chunk.getRowKey().isEmpty()) {
                    currentRowKey = chunk.getRowKey().toStringUtf8();
                    currentCells = new LinkedHashMap<>();
                }
                if (!chunk.getFamilyName().getValue().isEmpty()) {
                    currentFamily = chunk.getFamilyName().getValue();
                }
                if (!chunk.getQualifier().getValue().isEmpty()) {
                    currentQualifier = chunk.getQualifier().getValue();
                }
                if (!chunk.getValue().isEmpty() && currentFamily != null) {
                    currentCells.put(currentFamily + ":" + currentQualifier.toStringUtf8(),
                            chunk.getValue().toStringUtf8());
                }
                if (chunk.hasCommitRow() && currentRowKey != null) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("rowKey", currentRowKey);
                    row.put("cells", currentCells);
                    rows.add(row);
                    currentRowKey = null;
                    currentFamily = null;
                    currentQualifier = ByteString.EMPTY;
                    currentCells = new LinkedHashMap<>();
                }
            }
        }
        return rows;
    }

    /**
     * Modify column families on a table (ADD or DROP).
     */
    public void modifyColumnFamilies(String projectId, String instanceId, String tableId,
                              List<String> addFamilies, List<String> dropFamilies) {
        ModifyColumnFamiliesRequest.Builder request = ModifyColumnFamiliesRequest.newBuilder()
                .setName(tableName(projectId, instanceId, tableId));
        if (addFamilies != null) {
            for (String family : addFamilies) {
                request.addModifications(ModifyColumnFamiliesRequest.Modification.newBuilder()
                        .setId(family)
                        .setCreate(ColumnFamily.newBuilder().build())
                        .build());
            }
        }
        if (dropFamilies != null) {
            for (String family : dropFamilies) {
                request.addModifications(ModifyColumnFamiliesRequest.Modification.newBuilder()
                        .setId(family)
                        .setDrop(true)
                        .build());
            }
        }
        tableAdmin.modifyColumnFamilies(request.build());
    }

    /**
     * Drop a row range from a table.
     */
    public void dropRowRange(String projectId, String instanceId, String tableId,
                      String rowKeyPrefix) {
        tableAdmin.dropRowRange(DropRowRangeRequest.newBuilder()
                .setName(tableName(projectId, instanceId, tableId))
                .setRowKeyPrefix(ByteString.copyFromUtf8(rowKeyPrefix))
                .build());
    }

    /**
     * Atomic read-modify-write: increment a counter column.
     */
    public void readModifyWriteRow(String projectId, String instanceId, String tableId,
                            String rowKey, String family, String qualifier, long incrementAmount) {
        data.readModifyWriteRow(ReadModifyWriteRowRequest.newBuilder()
                .setTableName(tableName(projectId, instanceId, tableId))
                .setRowKey(ByteString.copyFromUtf8(rowKey))
                .addRules(ReadModifyWriteRule.newBuilder()
                        .setFamilyName(family)
                        .setColumnQualifier(ByteString.copyFromUtf8(qualifier))
                        .setIncrementAmount(incrementAmount)
                        .build())
                .build());
    }

    /**
     * Get column families for a table.
     */
    public List<String> getColumnFamilies(String projectId, String instanceId, String tableId) {
        Table table = tableAdmin.getTable(GetTableRequest.newBuilder()
                .setName(tableName(projectId, instanceId, tableId))
                .setView(Table.View.SCHEMA_VIEW)
                .build());
        return new ArrayList<>(table.getColumnFamiliesMap().keySet());
    }

    @Override
    public void close() {
        channel.shutdown();
        try {
            channel.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<String> families(Map<String, Object> cells) {
        return new ArrayList<>(flattenCells(cells).keySet().stream()
                .map(k -> {
                    int idx = k.indexOf(':');
                    return idx > 0 ? k.substring(0, idx) : "cf1";
                })
                .distinct()
                .toList());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> flattenCells(Map<String, Object> cells) {
        Map<String, Object> flat = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : cells.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> nested) {
                for (Map.Entry<?, ?> nestedEntry : ((Map<?, ?>) nested).entrySet()) {
                    flat.put(entry.getKey() + ":" + String.valueOf(nestedEntry.getKey()), nestedEntry.getValue());
                }
            } else {
                flat.put(entry.getKey(), entry.getValue());
            }
        }
        return flat;
    }

    private static String project(String projectId) {
        return "projects/" + projectId;
    }

    private static String instanceName(String projectId, String instanceId) {
        return project(projectId) + "/instances/" + instanceId;
    }

    private static String tableName(String projectId, String instanceId, String tableId) {
        return instanceName(projectId, instanceId) + "/tables/" + tableId;
    }

    private static String tail(String value, String marker) {
        int idx = value.lastIndexOf(marker);
        return idx >= 0 ? value.substring(idx + marker.length()) : value;
    }
}
