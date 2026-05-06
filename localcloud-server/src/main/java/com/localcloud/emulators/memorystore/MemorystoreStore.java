package com.localcloud.emulators.memorystore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.datasketches.hll.HllSketch;
import org.apache.datasketches.hll.Union;
import org.apache.datasketches.hll.TgtHllType;
import org.roaringbitmap.RoaringBitmap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localcloud.persistence.PostgresDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PostgreSQL JSONB persistence layer for the Memorystore (Redis) emulator.
 * All data is stored in the redis_data table with lazy TTL expiry.
 */
public class MemorystoreStore {

    private static final Logger logger = LoggerFactory.getLogger(MemorystoreStore.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** Common WHERE clause fragment for lazy TTL expiry. */
    private static final String TTL_FILTER = " AND (ttl_expires_at IS NULL OR ttl_expires_at > NOW())";

    private final PostgresDataSource dataSource;
    private final String projectId;

    public MemorystoreStore(PostgresDataSource dataSource, String projectId) {
        this.dataSource = dataSource;
        this.projectId = projectId;
    }

    // ---- String operations ----

    public String getString(int db, String key) {
        String sql = "SELECT value FROM redis_data WHERE project_id = ? AND db_number = ? AND key_name = ? AND data_type = 'string'" + TTL_FILTER;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, db);
            ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return parseJsonString(rs.getString("value"));
                }
                return null;
            }
        } catch (SQLException e) {
            logger.error("Failed to get string key={}: {}", key, e.getMessage());
            return null;
        }
    }

    public void setString(int db, String key, String value, long ttlSeconds) {
        String jsonValue = toJsonString(value);
        String sql = "INSERT INTO redis_data (project_id, db_number, key_name, data_type, value, ttl_expires_at) " +
                     "VALUES (?, ?, ?, 'string', ?::jsonb, " + ttlExpr(ttlSeconds) + ") " +
                     "ON CONFLICT (project_id, db_number, key_name) DO UPDATE SET " +
                     "data_type = 'string', value = EXCLUDED.value, ttl_expires_at = EXCLUDED.ttl_expires_at";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, db);
            ps.setString(3, key);
            ps.setObject(4, jsonValue, Types.OTHER);
            if (ttlSeconds > 0) {
                ps.setLong(5, ttlSeconds);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to set string key={}: {}", key, e.getMessage());
        }
    }

    /**
     * SET with NX (only if not exists) or XX (only if exists).
     * Uses atomic operations to avoid race conditions.
     * @return true if the key was set, false otherwise.
     */
    public boolean setStringConditional(int db, String key, String value, long ttlSeconds, boolean nx, boolean xx) {
        if (nx) {
            // NX: Only set if key does NOT exist - use atomic INSERT with ON CONFLICT DO NOTHING
            // If a row is inserted, the key didn't exist. If 0 rows affected, it existed.
            String jsonValue = toJsonString(value);
            String sql = "INSERT INTO redis_data (project_id, db_number, key_name, data_type, value, ttl_expires_at) " +
                         "VALUES (?, ?, ?, 'string', ?::jsonb, " + ttlExpr(ttlSeconds) + ") " +
                         "ON CONFLICT (project_id, db_number, key_name) DO NOTHING";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, projectId);
                ps.setInt(2, db);
                ps.setString(3, key);
                ps.setObject(4, jsonValue, Types.OTHER);
                if (ttlSeconds > 0) {
                    ps.setLong(5, ttlSeconds);
                }
                int rows = ps.executeUpdate();
                return rows > 0;
            } catch (SQLException e) {
                logger.error("Failed to set NX string key={}: {}", key, e.getMessage());
                return false;
            }
        }
        if (xx) {
            // XX: Only set if key EXISTS - use atomic UPDATE and check affected rows
            String jsonValue = toJsonString(value);
            String sql = "UPDATE redis_data SET data_type = 'string', value = ?::jsonb, ttl_expires_at = " + ttlExpr(ttlSeconds) +
                         "WHERE project_id = ? AND db_number = ? AND key_name = ? AND data_type = 'string'" +
                         (ttlSeconds > 0 ? "" : " OR ttl_expires_at IS NOT NULL AND ttl_expires_at <= now()");
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, jsonValue, Types.OTHER);
                ps.setString(2, projectId);
                ps.setInt(3, db);
                ps.setString(4, key);
                if (ttlSeconds > 0) {
                    ps.setLong(5, ttlSeconds);
                }
                int rows = ps.executeUpdate();
                return rows > 0;
            } catch (SQLException e) {
                logger.error("Failed to set XX string key={}: {}", key, e.getMessage());
                return false;
            }
        }
        setString(db, key, value, ttlSeconds);
        return true;
    }

    // ---- Hash operations ----

    public Map<String, String> getHash(int db, String key) {
        String sql = "SELECT value FROM redis_data WHERE project_id = ? AND db_number = ? AND key_name = ? AND data_type = 'hash'" + TTL_FILTER;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, db);
            ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapper.readValue(rs.getString("value"), new TypeReference<LinkedHashMap<String, String>>() {});
                }
                return null;
            }
        } catch (SQLException | JsonProcessingException e) {
            logger.error("Failed to get hash key={}: {}", key, e.getMessage());
            return null;
        }
    }

    public int setHashFields(int db, String key, Map<String, String> fields) {
        Map<String, String> existing = getHash(db, key);
        int newFields = 0;
        if (existing == null) {
            existing = new LinkedHashMap<>();
            newFields = fields.size();
        } else {
            for (String f : fields.keySet()) {
                if (!existing.containsKey(f)) newFields++;
            }
        }
        existing.putAll(fields);
        upsertRaw(db, key, "hash", toJson(existing), 0);
        return newFields;
    }

    public int removeHashFields(int db, String key, List<String> fieldNames) {
        Map<String, String> existing = getHash(db, key);
        if (existing == null) return 0;
        int removed = 0;
        for (String f : fieldNames) {
            if (existing.remove(f) != null) removed++;
        }
        if (existing.isEmpty()) {
            deleteKeys(db, List.of(key));
        } else {
            upsertRaw(db, key, "hash", toJson(existing), 0);
        }
        return removed;
    }

    // ---- List operations ----

    public List<String> getList(int db, String key) {
        String sql = "SELECT value FROM redis_data WHERE project_id = ? AND db_number = ? AND key_name = ? AND data_type = 'list'" + TTL_FILTER;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, db);
            ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapper.readValue(rs.getString("value"), new TypeReference<List<String>>() {});
                }
                return null;
            }
        } catch (SQLException | JsonProcessingException e) {
            logger.error("Failed to get list key={}: {}", key, e.getMessage());
            return null;
        }
    }

    public long listPush(int db, String key, List<String> values, boolean leftSide) {
        List<String> existing = getList(db, key);
        if (existing == null) existing = new ArrayList<>();
        if (leftSide) {
            // LPUSH: prepend in reverse order so the last argument ends up at head
            for (int i = values.size() - 1; i >= 0; i--) {
                existing.add(0, values.get(i));
            }
        } else {
            existing.addAll(values);
        }
        upsertRaw(db, key, "list", toJson(existing), 0);
        return existing.size();
    }

    public String listPop(int db, String key, boolean leftSide) {
        List<String> existing = getList(db, key);
        if (existing == null || existing.isEmpty()) return null;
        String val = leftSide ? existing.remove(0) : existing.remove(existing.size() - 1);
        if (existing.isEmpty()) {
            deleteKeys(db, List.of(key));
        } else {
            upsertRaw(db, key, "list", toJson(existing), 0);
        }
        return val;
    }

    public List<String> listRange(int db, String key, int start, int stop) {
        List<String> existing = getList(db, key);
        if (existing == null) return List.of();
        int len = existing.size();
        // Normalize negative indices
        if (start < 0) start = len + start;
        if (stop < 0) stop = len + stop;
        if (start < 0) start = 0;
        if (stop >= len) stop = len - 1;
        if (start > stop) return List.of();
        return new ArrayList<>(existing.subList(start, stop + 1));
    }

    public int listLen(int db, String key) {
        List<String> existing = getList(db, key);
        return existing == null ? 0 : existing.size();
    }

    public String listIndex(int db, String key, int index) {
        List<String> existing = getList(db, key);
        if (existing == null) return null;
        int len = existing.size();
        if (index < 0) index = len + index;
        if (index < 0 || index >= len) return null;
        return existing.get(index);
    }

    public boolean listSet(int db, String key, int index, String value) {
        List<String> existing = getList(db, key);
        if (existing == null) return false;
        int len = existing.size();
        if (index < 0) index = len + index;
        if (index < 0 || index >= len) return false;
        existing.set(index, value);
        upsertRaw(db, key, "list", toJson(existing), 0);
        return true;
    }

    public void listTrim(int db, String key, int start, int stop) {
        List<String> existing = getList(db, key);
        if (existing == null) return;
        int len = existing.size();
        if (start < 0) start = len + start;
        if (stop < 0) stop = len + stop;
        if (start < 0) start = 0;
        if (stop >= len) stop = len - 1;
        if (start > stop) {
            deleteKeys(db, List.of(key));
            return;
        }
        List<String> trimmed = new ArrayList<>(existing.subList(start, stop + 1));
        if (trimmed.isEmpty()) {
            deleteKeys(db, List.of(key));
        } else {
            upsertRaw(db, key, "list", toJson(trimmed), 0);
        }
    }

    // ---- Set operations (using RoaringBitmap for efficient storage) ----

    private static RoaringBitmap deserializeSet(String json) throws JsonProcessingException {
        if (json == null || json.isEmpty()) return new RoaringBitmap();
        List<String> list = mapper.readValue(json, new TypeReference<List<String>>() {});
        RoaringBitmap rb = new RoaringBitmap();
        for (String item : list) {
            rb.add((int) hashStringToInt64(item));
        }
        return rb;
    }

    private static String serializeSet(RoaringBitmap rb) throws JsonProcessingException {
        if (rb == null || rb.isEmpty()) return "[]";
        List<String> list = new ArrayList<>();
        for (int i : rb) {
            list.add(hashInt64ToString(i));
        }
        return mapper.writeValueAsString(list);
    }

    private static long hashStringToInt64(String s) {
        return s.hashCode() & 0xFFFFFFFFL;
    }

    private static String hashInt64ToString(long hash) {
        return String.valueOf(hash);
    }

    public Set<String> getSetMembers(int db, String key) {
        String sql = "SELECT value FROM redis_data WHERE project_id = ? AND db_number = ? AND key_name = ? AND data_type = 'set'" + TTL_FILTER;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, db);
            ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return deserializeSetToSet(rs.getString("value"));
                }
                return null;
            }
        } catch (SQLException | JsonProcessingException e) {
            logger.error("Failed to get set key={}: {}", key, e.getMessage());
            return null;
        }
    }

    private java.util.HashSet<String> deserializeSetToSet(String json) throws JsonProcessingException {
        RoaringBitmap rb = deserializeSet(json);
        java.util.HashSet<String> result = new java.util.HashSet<>();
        for (int i : rb) {
            result.add(hashInt64ToString(i));
        }
        return result;
    }

    public int addSetMembers(int db, String key, List<String> members) {
        RoaringBitmap existing = getSetMembersRoaring(db, key);
        if (existing == null) existing = new RoaringBitmap();
        int added = 0;
        for (String m : members) {
            long h = hashStringToInt64(m);
            if (!existing.contains((int) h)) added++;
            existing.add((int) h);
        }
        try {
            upsertRaw(db, key, "set", serializeSet(existing), 0);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize set key={}: {}", key, e.getMessage());
        }
        return added;
    }

    public int removeSetMembers(int db, String key, List<String> members) {
        RoaringBitmap existing = getSetMembersRoaring(db, key);
        if (existing == null) return 0;
        int removed = 0;
        for (String m : members) {
            long h = hashStringToInt64(m);
            if (existing.contains((int) h)) removed++;
            existing.remove((int) h);
        }
        if (existing.isEmpty()) {
            deleteKeys(db, List.of(key));
        } else {
            try {
                upsertRaw(db, key, "set", serializeSet(existing), 0);
            } catch (JsonProcessingException e) {
                logger.error("Failed to serialize set key={}: {}", key, e.getMessage());
            }
        }
        return removed;
    }

    private RoaringBitmap getSetMembersRoaring(int db, String key) {
        String sql = "SELECT value FROM redis_data WHERE project_id = ? AND db_number = ? AND key_name = ? AND data_type = 'set'" + TTL_FILTER;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, db);
            ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return deserializeSet(rs.getString("value"));
                }
                return null;
            }
        } catch (SQLException | JsonProcessingException e) {
            logger.error("Failed to get set key={}: {}", key, e.getMessage());
            return null;
        }
    }

    // Set operations as List (for compatibility)
    public java.util.HashSet<String> getSetMembersList(int db, String key) {
        String sql = "SELECT value FROM redis_data WHERE project_id = ? AND db_number = ? AND key_name = ? AND data_type = 'set'" + TTL_FILTER;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, db);
            ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    RoaringBitmap rb = deserializeSet(rs.getString("value"));
                    java.util.HashSet<String> result = new java.util.HashSet<>();
                    for (int i : rb) {
                        result.add(hashInt64ToString(i));
                    }
                    return result;
                }
                return null;
            }
        } catch (SQLException | JsonProcessingException e) {
            logger.error("Failed to get set key={}: {}", key, e.getMessage());
            return null;
        }
    }

    public int addSetMembersList(int db, String key, List<String> members) {
        return addSetMembers(db, key, members);
    }

    public int removeSetMembersList(int db, String key, List<String> members) {
        return removeSetMembers(db, key, members);
    }

    // ---- HyperLogLog operations (using Apache DataSketches) ----

    public long pfAdd(int db, String key, List<String> values) {
        HllSketch sketch = getHllSketch(db, key);
        if (sketch == null) {
            sketch = new HllSketch(12, TgtHllType.HLL_4);
        }
        for (String v : values) {
            sketch.update(v.hashCode());
        }
        upsertRaw(db, key, "hll", java.util.Base64.getEncoder().encodeToString(sketch.toCompactByteArray()), 0);
        return (long) sketch.getEstimate();
    }

    public long pfCount(int db, String key) {
        HllSketch sketch = getHllSketch(db, key);
        return sketch != null ? (long) sketch.getEstimate() : 0;
    }

    public long pfCountMultiple(int db, List<String> keys) {
        Union union = new Union(12);
        for (String key : keys) {
            HllSketch sketch = getHllSketch(db, key);
            if (sketch != null) {
                union.update(sketch);
            }
        }
        HllSketch result = union.getResult(TgtHllType.HLL_4);
        return (long) result.getEstimate();
    }

    public long pfMerge(int db, String destKey, List<String> sourceKeys) {
        Union union = new Union(12);
        for (String key : sourceKeys) {
            HllSketch sketch = getHllSketch(db, key);
            if (sketch != null) {
                union.update(sketch);
            }
        }
        HllSketch merged = union.getResult(TgtHllType.HLL_4);
        upsertRaw(db, destKey, "hll", java.util.Base64.getEncoder().encodeToString(merged.toCompactByteArray()), 0);
        return (long) merged.getEstimate();
    }

    private HllSketch getHllSketch(int db, String key) {
        String sql = "SELECT value FROM redis_data WHERE project_id = ? AND db_number = ? AND key_name = ? AND data_type = 'hll'" + TTL_FILTER;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, db);
            ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String encoded = rs.getString("value");
                    if (encoded != null && !encoded.isEmpty()) {
                        byte[] bytes = java.util.Base64.getDecoder().decode(encoded);
                        return HllSketch.heapify(
                            org.apache.datasketches.memory.Memory.wrap(bytes));
                    }
                }
                return null;
            }
        } catch (Exception e) {
            logger.error("Failed to get HLL key={}: {}", key, e.getMessage());
            return null;
        }
    }

    // ---- Geospatial operations (GEO) ----

    private static final double EARTH_RADIUS_KM = 6371.0;

    public int geoAdd(int db, String key, List<GeoPoint> points) {
        List<GeoPoint> existing = geoGet(key);
        if (existing == null) existing = new ArrayList<>();
        for (GeoPoint p : points) {
            if (!existing.contains(p)) {
                existing.add(p);
            }
        }
        upsertData("geo", key, existing);
        return points.size();
    }

    public Double geoDist(int db, String key, String member1, String member2) {
        GeoPoint p1 = geoMember(key, member1);
        GeoPoint p2 = geoMember(key, member2);
        if (p1 == null || p2 == null) return null;
        return haversineDistance(p1.lng, p1.lat, p2.lng, p2.lat);
    }

    public String geoHash(int db, String key, String member) {
        GeoPoint p = geoMember(key, member);
        if (p == null) return null;
        return encodeGeohash(p.lat, p.lng);
    }

    public List<GeoPoint> geoSearch(int db, String key, double lng, double lat, double radius, String unit) {
        double radiusKm = "km".equalsIgnoreCase(unit) ? radius : radius * 1.60934;
        List<GeoPoint> existing = geoGet(key);
        if (existing == null) return List.of();
        
        List<GeoPoint> result = new ArrayList<>();
        for (GeoPoint p : existing) {
            double dist = haversineDistance(lng, lat, p.lng, p.lat);
            if (dist <= radiusKm) {
                result.add(p);
            }
        }
        return result;
    }

    public List<Double> geoPos(int db, String key, String... members) {
        List<Double> result = new ArrayList<>();
        for (String member : members) {
            GeoPoint p = geoMember(key, member);
            if (p != null) {
                result.add(p.lng);
                result.add(p.lat);
            }
        }
        return result;
    }

    private record GeoPoint(String member, double lng, double lat) {}

    private List<GeoPoint> geoGet(String key) {
        String sql = "SELECT value FROM redis_data WHERE project_id = ? AND db_number = ? AND key_name = ? AND data_type = 'geo'" + TTL_FILTER;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, 0);
            ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapper.readValue(rs.getString("value"), new TypeReference<List<GeoPoint>>() {});
                }
                return null;
            }
        } catch (SQLException | JsonProcessingException e) {
            logger.error("Failed to get geo key={}: {}", key, e.getMessage());
            return null;
        }
    }

    private void upsertData(String dataType, String key, Object data) {
        try {
            upsertRaw(0, key, dataType, toJson(data), 0);
        } catch (Exception e) {
            logger.error("Failed to upsert data key={}: {}", key, e.getMessage());
        }
    }

    private GeoPoint geoMember(String key, String member) {
        List<GeoPoint> points = geoGet(key);
        if (points == null) return null;
        for (GeoPoint p : points) {
            if (p.member().equals(member)) return p;
        }
        return null;
    }

    private static double haversineDistance(double lng1, double lat1, double lng2, double lat2) {
        double dLng = Math.toRadians(lng2 - lng1);
        double dLat = Math.toRadians(lat2 - lat1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                  Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                  Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    private static String encodeGeohash(double lat, double lng) {
        // Simple geohash encoding (base32)
        StringBuilder sb = new StringBuilder();
        double latMin = -90, latMax = 90;
        double lngMin = -180, lngMax = 180;
        boolean even = true;
        int bits = 0;
        int ch = 0;
        
        for (int i = 0; i < 12; i++) {
            if (even) {
                double mid = (lngMin + lngMax) / 2;
                ch = (ch << 1) | (lng >= mid ? 1 : 0);
                if (lng >= mid) lngMin = mid;
                else lngMax = mid;
            } else {
                double mid = (latMin + latMax) / 2;
                ch = (ch << 1) | (lat >= mid ? 1 : 0);
                if (lat >= mid) latMin = mid;
                else latMax = mid;
            }
            even = !even;
            bits++;
            if (bits == 5) {
                sb.append("0123456789bcdefghjkmnpqrstuvwxyz".charAt(ch));
                bits = 0;
                ch = 0;
            }
        }
        return sb.toString();
    }

    // ---- Sorted Set operations ----

    public record SortedSetEntry(String member, double score) {}

    public List<SortedSetEntry> getSortedSet(int db, String key) {
        String sql = "SELECT value FROM redis_data WHERE project_id = ? AND db_number = ? AND key_name = ? AND data_type = 'zset'" + TTL_FILTER;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, db);
            ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    List<Map<String, Object>> raw = mapper.readValue(rs.getString("value"),
                            new TypeReference<List<Map<String, Object>>>() {});
                    List<SortedSetEntry> entries = new ArrayList<>();
                    for (var m : raw) {
                        String member = (String) m.get("m");
                        double score = ((Number) m.get("s")).doubleValue();
                        entries.add(new SortedSetEntry(member, score));
                    }
                    entries.sort((a, b) -> {
                        int cmp = Double.compare(a.score, b.score);
                        return cmp != 0 ? cmp : a.member.compareTo(b.member);
                    });
                    return entries;
                }
                return null;
            }
        } catch (SQLException | JsonProcessingException e) {
            logger.error("Failed to get sorted set key={}: {}", key, e.getMessage());
            return null;
        }
    }

    public int addSortedSetMembers(int db, String key, List<SortedSetEntry> entries) {
        List<SortedSetEntry> existing = getSortedSet(db, key);
        Map<String, Double> map = new LinkedHashMap<>();
        if (existing != null) {
            for (var e : existing) map.put(e.member, e.score);
        }
        int added = 0;
        for (var e : entries) {
            if (!map.containsKey(e.member)) added++;
            map.put(e.member, e.score);
        }
        List<Map<String, Object>> raw = new ArrayList<>();
        for (var entry : map.entrySet()) {
            raw.add(Map.of("m", entry.getKey(), "s", entry.getValue()));
        }
        upsertRaw(db, key, "zset", toJson(raw), 0);
        return added;
    }

    public int removeSortedSetMembers(int db, String key, List<String> members) {
        List<SortedSetEntry> existing = getSortedSet(db, key);
        if (existing == null) return 0;
        Set<String> toRemove = new java.util.LinkedHashSet<>(members);
        int removed = 0;
        List<SortedSetEntry> remaining = new ArrayList<>();
        for (var e : existing) {
            if (toRemove.contains(e.member)) {
                removed++;
            } else {
                remaining.add(e);
            }
        }
        if (remaining.isEmpty()) {
            deleteKeys(db, List.of(key));
        } else {
            List<Map<String, Object>> raw = new ArrayList<>();
            for (var e : remaining) {
                raw.add(Map.of("m", e.member, "s", e.score));
            }
            upsertRaw(db, key, "zset", toJson(raw), 0);
        }
        return removed;
    }

    // ---- Key operations ----

    public int deleteKeys(int db, List<String> keys) {
        if (keys.isEmpty()) return 0;
        // Build IN clause
        StringBuilder sb = new StringBuilder("DELETE FROM redis_data WHERE project_id = ? AND db_number = ? AND key_name IN (");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("?");
        }
        sb.append(")");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            ps.setString(1, projectId);
            ps.setInt(2, db);
            for (int i = 0; i < keys.size(); i++) {
                ps.setString(3 + i, keys.get(i));
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to delete keys: {}", e.getMessage());
            return 0;
        }
    }

    public boolean exists(int db, String key) {
        String sql = "SELECT 1 FROM redis_data WHERE project_id = ? AND db_number = ? AND key_name = ?" + TTL_FILTER;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, db);
            ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            logger.error("Failed to check exists key={}: {}", key, e.getMessage());
            return false;
        }
    }

    public String type(int db, String key) {
        String sql = "SELECT data_type FROM redis_data WHERE project_id = ? AND db_number = ? AND key_name = ?" + TTL_FILTER;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, db);
            ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("data_type");
                }
                return null;
            }
        } catch (SQLException e) {
            logger.error("Failed to get type key={}: {}", key, e.getMessage());
            return null;
        }
    }

    public boolean setExpire(int db, String key, long ttlMs) {
        String sql = "UPDATE redis_data SET ttl_expires_at = NOW() + (? || ' milliseconds')::INTERVAL " +
                     "WHERE project_id = ? AND db_number = ? AND key_name = ?" + TTL_FILTER;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, String.valueOf(ttlMs));
            ps.setString(2, projectId);
            ps.setInt(3, db);
            ps.setString(4, key);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Failed to set expire key={}: {}", key, e.getMessage());
            return false;
        }
    }

    public boolean setExpireAt(int db, String key, long unixTimestampSeconds) {
        String sql = "UPDATE redis_data SET ttl_expires_at = TO_TIMESTAMP(?) " +
                     "WHERE project_id = ? AND db_number = ? AND key_name = ?" + TTL_FILTER;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, unixTimestampSeconds);
            ps.setString(2, projectId);
            ps.setInt(3, db);
            ps.setString(4, key);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Failed to set expireat key={}: {}", key, e.getMessage());
            return false;
        }
    }

    /**
     * @return TTL in milliseconds, -1 if no expiry, -2 if key does not exist.
     */
    public long getTtl(int db, String key) {
        String sql = "SELECT ttl_expires_at FROM redis_data WHERE project_id = ? AND db_number = ? AND key_name = ?" + TTL_FILTER;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, db);
            ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return -2;
                java.sql.Timestamp ts = rs.getTimestamp("ttl_expires_at");
                if (ts == null) return -1;
                long remaining = ts.getTime() - System.currentTimeMillis();
                return remaining > 0 ? remaining : -2;
            }
        } catch (SQLException e) {
            logger.error("Failed to get ttl key={}: {}", key, e.getMessage());
            return -2;
        }
    }

    public boolean persist(int db, String key) {
        String sql = "UPDATE redis_data SET ttl_expires_at = NULL WHERE project_id = ? AND db_number = ? AND key_name = ?" + TTL_FILTER;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, db);
            ps.setString(3, key);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Failed to persist key={}: {}", key, e.getMessage());
            return false;
        }
    }

    public void rename(int db, String oldKey, String newKey) {
        try (Connection conn = dataSource.getConnection()) {
            // Delete newKey if exists
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM redis_data WHERE project_id = ? AND db_number = ? AND key_name = ?")) {
                ps.setString(1, projectId);
                ps.setInt(2, db);
                ps.setString(3, newKey);
                ps.executeUpdate();
            }
            // Rename oldKey to newKey
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE redis_data SET key_name = ? WHERE project_id = ? AND db_number = ? AND key_name = ?")) {
                ps.setString(1, newKey);
                ps.setString(2, projectId);
                ps.setInt(3, db);
                ps.setString(4, oldKey);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.error("Failed to rename key {} -> {}: {}", oldKey, newKey, e.getMessage());
        }
    }

    public List<String> keys(int db, String pattern) {
        // Convert Redis glob pattern to SQL LIKE pattern
        String likePattern = globToLike(pattern);
        String sql = "SELECT key_name FROM redis_data WHERE project_id = ? AND db_number = ? AND key_name LIKE ?" + TTL_FILTER +
                     " ORDER BY key_name";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, db);
            ps.setString(3, likePattern);
            List<String> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString("key_name"));
                }
            }
            return result;
        } catch (SQLException e) {
            logger.error("Failed to get keys pattern={}: {}", pattern, e.getMessage());
            return List.of();
        }
    }

    public long dbSize(int db) {
        String sql = "SELECT COUNT(*) FROM redis_data WHERE project_id = ? AND db_number = ?" + TTL_FILTER;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, db);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            logger.error("Failed to get dbsize: {}", e.getMessage());
            return 0;
        }
    }

    public void flushDb(int db) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM redis_data WHERE project_id = ? AND db_number = ?")) {
            ps.setString(1, projectId);
            ps.setInt(2, db);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to flush db {}: {}", db, e.getMessage());
        }
    }

    public void flushAll() {
        try (Connection conn = dataSource.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM redis_data");
            logger.info("Flushed all Redis data");
        } catch (SQLException e) {
            logger.error("Failed to flush all: {}", e.getMessage());
        }
    }

    /**
     * Get raw value for data browsing.
     */
    public String getValue(int db, String key) {
        String sql = "SELECT value, data_type FROM redis_data WHERE project_id = ? AND db_number = ? AND key_name = ?" + TTL_FILTER;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, db);
            ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("value");
                }
                return null;
            }
        } catch (SQLException e) {
            logger.error("Failed to get raw value key={}: {}", key, e.getMessage());
            return null;
        }
    }

    // ---- Internal helpers ----

    private void upsertRaw(int db, String key, String dataType, String jsonValue, long ttlSeconds) {
        String sql = "INSERT INTO redis_data (project_id, db_number, key_name, data_type, value, ttl_expires_at) " +
                     "VALUES (?, ?, ?, ?, ?::jsonb, " + ttlExpr(ttlSeconds) + ") " +
                     "ON CONFLICT (project_id, db_number, key_name) DO UPDATE SET " +
                     "data_type = EXCLUDED.data_type, value = EXCLUDED.value" +
                     (ttlSeconds > 0 ? ", ttl_expires_at = EXCLUDED.ttl_expires_at" : "");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, db);
            ps.setString(3, key);
            ps.setString(4, dataType);
            ps.setObject(5, jsonValue, Types.OTHER);
            if (ttlSeconds > 0) {
                ps.setLong(6, ttlSeconds);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to upsert key={} type={}: {}", key, dataType, e.getMessage());
        }
    }

    /**
     * Returns the SQL expression for setting TTL. Uses a parameter placeholder if ttlSeconds > 0.
     */
    private static String ttlExpr(long ttlSeconds) {
        if (ttlSeconds <= 0) return "NULL";
        return "NOW() + (? || ' seconds')::INTERVAL";
    }

    private static String toJsonString(String value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "\"\"";
        }
    }

    private static String parseJsonString(String json) {
        try {
            return mapper.readValue(json, String.class);
        } catch (JsonProcessingException e) {
            // Fallback: strip surrounding quotes
            if (json != null && json.startsWith("\"") && json.endsWith("\"")) {
                return json.substring(1, json.length() - 1);
            }
            return json;
        }
    }

    private static String toJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /**
     * Convert Redis glob pattern to SQL LIKE pattern.
     * '*' -> '%', '?' -> '_', escape '%' and '_' in the original.
     */
    static String globToLike(String glob) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append('%');
                case '?' -> sb.append('_');
                case '%' -> sb.append("\\%");
                case '_' -> sb.append("\\_");
                case '\\' -> {
                    // Escaped character in glob - pass through literally
                    if (i + 1 < glob.length()) {
                        char next = glob.charAt(++i);
                        if (next == '%' || next == '_') {
                            sb.append('\\').append(next);
                        } else {
                            sb.append(next);
                        }
                    }
                }
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
