package dev.braintrust.devserver;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LRUCacheTest {

    @Test
    void testBasicPutAndGet() {
        LRUCache<String, String> cache = new LRUCache<>(3);

        cache.put("key1", "value1");
        cache.put("key2", "value2");

        assertEquals("value1", cache.get("key1"));
        assertEquals("value2", cache.get("key2"));
        assertNull(cache.get("key3"));
    }

    @Test
    void testLruEviction() {
        LRUCache<String, String> cache = new LRUCache<>(2);

        cache.put("key1", "value1");
        cache.put("key2", "value2");
        assertEquals(2, cache.size());

        // Adding third item should evict the least recently used (key1)
        cache.put("key3", "value3");
        assertEquals(2, cache.size());
        assertNull(cache.get("key1"), "key1 should have been evicted");
        assertEquals("value2", cache.get("key2"));
        assertEquals("value3", cache.get("key3"));
    }

    @Test
    void testLruEvictionWithAccess() {
        LRUCache<String, String> cache = new LRUCache<>(2);

        cache.put("key1", "value1");
        cache.put("key2", "value2");

        // Access key1 to make it more recently used
        cache.get("key1");

        // Adding third item should now evict key2 (least recently used)
        cache.put("key3", "value3");
        assertEquals(2, cache.size());
        assertEquals("value1", cache.get("key1"), "key1 should still be present");
        assertNull(cache.get("key2"), "key2 should have been evicted");
        assertEquals("value3", cache.get("key3"));
    }

    @Test
    void testGetOrComputeCacheHit() {
        LRUCache<String, String> cache = new LRUCache<>(3);
        AtomicInteger computeCount = new AtomicInteger(0);

        // First call should compute and cache
        String result1 =
                cache.getOrCompute(
                        "key1",
                        () -> {
                            computeCount.incrementAndGet();
                            return "computed-value";
                        });
        assertEquals("computed-value", result1);
        assertEquals(1, computeCount.get());

        // Second call should return cached value without computing
        String result2 =
                cache.getOrCompute(
                        "key1",
                        () -> {
                            computeCount.incrementAndGet();
                            return "should-not-be-called";
                        });
        assertEquals("computed-value", result2);
        assertEquals(1, computeCount.get(), "Supplier should not have been called on cache hit");
    }

    @Test
    void testGetOrComputeCacheMiss() {
        LRUCache<String, String> cache = new LRUCache<>(3);
        AtomicInteger computeCount = new AtomicInteger(0);

        String result =
                cache.getOrCompute(
                        "key1",
                        () -> {
                            computeCount.incrementAndGet();
                            return "value-" + computeCount.get();
                        });

        assertEquals("value-1", result);
        assertEquals(1, computeCount.get());
        assertTrue(cache.containsKey("key1"));
        assertEquals("value-1", cache.get("key1"));
    }

    @Test
    void testContainsKey() {
        LRUCache<String, String> cache = new LRUCache<>(3);

        assertFalse(cache.containsKey("key1"));

        cache.put("key1", "value1");
        assertTrue(cache.containsKey("key1"));
        assertFalse(cache.containsKey("key2"));
    }

    @Test
    void testClear() {
        LRUCache<String, String> cache = new LRUCache<>(3);

        cache.put("key1", "value1");
        cache.put("key2", "value2");
        assertEquals(2, cache.size());

        cache.clear();
        assertEquals(0, cache.size());
        assertFalse(cache.containsKey("key1"));
        assertFalse(cache.containsKey("key2"));
    }

    @Test
    void testSize() {
        LRUCache<String, String> cache = new LRUCache<>(5);

        assertEquals(0, cache.size());

        cache.put("key1", "value1");
        assertEquals(1, cache.size());

        cache.put("key2", "value2");
        cache.put("key3", "value3");
        assertEquals(3, cache.size());

        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test
    void testMaxSizeEnforcement() {
        LRUCache<Integer, String> cache = new LRUCache<>(3);

        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        assertEquals(3, cache.size());

        // Adding more items should maintain max size
        cache.put(4, "four");
        assertEquals(3, cache.size(), "Cache should not exceed max size");

        cache.put(5, "five");
        cache.put(6, "six");
        assertEquals(3, cache.size(), "Cache should not exceed max size");
    }
}
