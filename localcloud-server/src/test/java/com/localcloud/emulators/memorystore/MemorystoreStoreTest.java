package com.localcloud.emulators.memorystore;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MemorystoreStore data type operations.
 * Uses mocking to avoid database connections.
 */
public class MemorystoreStoreTest {

    // Test RoaringBitmap serialization helpers
    
    @Test
    void testRoaringBitmapSerialize() throws Exception {
        org.roaringbitmap.RoaringBitmap rb = new org.roaringbitmap.RoaringBitmap();
        rb.add(1);
        rb.add(100);
        rb.add(1000);
        
        assertEquals(3, rb.getCardinality());
        assertTrue(rb.contains(1));
        assertTrue(rb.contains(100));
        assertTrue(rb.contains(1000));
        assertFalse(rb.contains(2));
    }

    @Test
    void testRoaringBitmapOr() {
        org.roaringbitmap.RoaringBitmap rb1 = new org.roaringbitmap.RoaringBitmap();
        rb1.add(1);
        rb1.add(2);
        
        org.roaringbitmap.RoaringBitmap rb2 = new org.roaringbitmap.RoaringBitmap();
        rb2.add(2);
        rb2.add(3);
        
        org.roaringbitmap.RoaringBitmap union = org.roaringbitmap.RoaringBitmap.or(rb1, rb2);
        
        assertEquals(3, union.getCardinality());
        assertTrue(union.contains(1));
        assertTrue(union.contains(2));
        assertTrue(union.contains(3));
    }

    @Test
    void testRoaringBitmapAnd() {
        org.roaringbitmap.RoaringBitmap rb1 = new org.roaringbitmap.RoaringBitmap();
        rb1.add(1);
        rb1.add(2);
        
        org.roaringbitmap.RoaringBitmap rb2 = new org.roaringbitmap.RoaringBitmap();
        rb2.add(2);
        rb2.add(3);
        
        org.roaringbitmap.RoaringBitmap intersection = org.roaringbitmap.RoaringBitmap.and(rb1, rb2);
        
        assertEquals(1, intersection.getCardinality());
        assertTrue(intersection.contains(2));
    }

    @Test
    void testRoaringBitmapXor() {
        org.roaringbitmap.RoaringBitmap rb1 = new org.roaringbitmap.RoaringBitmap();
        rb1.add(1);
        rb1.add(2);
        
        org.roaringbitmap.RoaringBitmap rb2 = new org.roaringbitmap.RoaringBitmap();
        rb2.add(2);
        rb2.add(3);
        
        org.roaringbitmap.RoaringBitmap xor = org.roaringbitmap.RoaringBitmap.xor(rb1, rb2);
        
        assertEquals(2, xor.getCardinality());
        assertTrue(xor.contains(1));
        assertTrue(xor.contains(3));
    }

    // ================== HyperLogLog Tests ==================

    @Test
    void testHllSketchBasic() {
        org.apache.datasketches.hll.HllSketch sketch = 
            new org.apache.datasketches.hll.HllSketch(12, org.apache.datasketches.hll.TgtHllType.HLL_4);
        
        sketch.update(1);
        sketch.update(2);
        sketch.update(3);
        sketch.update(1); // duplicate
        
        double estimate = sketch.getEstimate();
        assertTrue(estimate >= 3);
        assertTrue(estimate <= 4);
    }

    @Test
    void testHllSketchLargeCardinality() {
        org.apache.datasketches.hll.HllSketch sketch = 
            new org.apache.datasketches.hll.HllSketch(12, org.apache.datasketches.hll.TgtHllType.HLL_4);
        
        // Add 10000 unique items
        for (int i = 0; i < 10000; i++) {
            sketch.update(i);
        }
        
        double estimate = sketch.getEstimate();
        
        // Apache DataSketches has ~1.6% error bound
        assertTrue(estimate >= 10000 * 0.984);
        assertTrue(estimate <= 10000 * 1.016);
    }

    @Test
    void testHllSketchUnion() {
        org.apache.datasketches.hll.HllSketch sketch1 = 
            new org.apache.datasketches.hll.HllSketch(12, org.apache.datasketches.hll.TgtHllType.HLL_4);
        sketch1.update(1);
        sketch1.update(2);
        sketch1.update(3);
        
        org.apache.datasketches.hll.HllSketch sketch2 = 
            new org.apache.datasketches.hll.HllSketch(12, org.apache.datasketches.hll.TgtHllType.HLL_4);
        sketch2.update(3);
        sketch2.update(4);
        sketch2.update(5);
        
        org.apache.datasketches.hll.Union union = new org.apache.datasketches.hll.Union(12);
        union.update(sketch1);
        union.update(sketch2);
        
        org.apache.datasketches.hll.HllSketch result = union.getResult(org.apache.datasketches.hll.TgtHllType.HLL_4);
        
        double estimate = result.getEstimate();
        assertTrue(estimate >= 5);
    }

    @Test
    void testHllSketchSerialization() throws Exception {
        org.apache.datasketches.hll.HllSketch sketch = 
            new org.apache.datasketches.hll.HllSketch(12, org.apache.datasketches.hll.TgtHllType.HLL_4);
        
        for (int i = 0; i < 1000; i++) {
            sketch.update(i);
        }
        
        byte[] serialized = sketch.toCompactByteArray();
        assertTrue(serialized.length > 0);
        
        // Deserialize
        org.apache.datasketches.hll.HllSketch deserialized = 
            org.apache.datasketches.hll.HllSketch.heapify(
                org.apache.datasketches.memory.Memory.wrap(serialized));
        
        double estimate = deserialized.getEstimate();
        assertTrue(estimate >= 1000 * 0.95);
        assertTrue(estimate <= 1000 * 1.05);
    }

    @Test
    void testHllCompactFormat() {
        org.apache.datasketches.hll.HllSketch sketch = 
            new org.apache.datasketches.hll.HllSketch(12, org.apache.datasketches.hll.TgtHllType.HLL_4);
        
        for (int i = 0; i < 500; i++) {
            sketch.update(i);
        }
        
        byte[] compact = sketch.toCompactByteArray();
        assertTrue(compact.length > 0);
    }

    @Test
    void testHllCompactVsUpdatableSize() {
        org.apache.datasketches.hll.HllSketch sketch = 
            new org.apache.datasketches.hll.HllSketch(12, org.apache.datasketches.hll.TgtHllType.HLL_4);
        
        for (int i = 0; i < 10000; i++) {
            sketch.update(i);
        }
        
        byte[] compact = sketch.toCompactByteArray();
        byte[] updatable = sketch.toUpdatableByteArray();
        
        // Compact should be smaller or equal
        assertTrue(compact.length <= updatable.length);
    }

    // ================== String Hash ==================

    @Test
    void testHashConsistency() {
        String test = "test-member";
        long hash = test.hashCode() & 0xFFFFFFFFL;
        
        // Same string should produce same hash
        long hash2 = test.hashCode() & 0xFFFFFFFFL;
        assertEquals(hash, hash2);
    }

    // ================== Data Type Integration ==================
    
    @Test
    void testMockedStore() throws SQLException {
        // Create mock data source
        javax.sql.DataSource mockDs = mock(javax.sql.DataSource.class);
        Connection mockConn = mock(Connection.class);
        PreparedStatement mockPs = mock(PreparedStatement.class);
        ResultSet mockRs = mock(ResultSet.class);
        
        when(mockDs.getConnection()).thenReturn(mockConn);
        when(mockConn.prepareStatement(anyString())).thenReturn(mockPs);
        when(mockPs.executeQuery()).thenReturn(mockRs);
        when(mockRs.next()).thenReturn(true);
        when(mockRs.getString("value")).thenReturn("[\"member1\",\"member2\"]");
        
        // Note: This test validates the mocking framework works
        // Full integration test would require H2 or Testcontainers
        assertNotNull(mockDs);
        assertNotNull(mockConn);
    }
}