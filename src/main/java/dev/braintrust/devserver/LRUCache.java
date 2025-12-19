package dev.braintrust.devserver;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Simple LRU (Least Recently Used) cache implementation.
 *
 * <p>Thread-safe cache with a maximum size. When the cache exceeds its capacity, the least recently
 * used entry is evicted.
 *
 * @param <K> Key type
 * @param <V> Value type
 */
@ThreadSafe
class LRUCache<K, V> {
    private final int maxSize;
    private final Map<K, V> cache;

    public LRUCache(int maxSize) {
        this.maxSize = maxSize;
        this.cache =
                new LinkedHashMap<K, V>(maxSize, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                        return size() > LRUCache.this.maxSize;
                    }
                };
    }

    public synchronized void put(K key, V value) {
        cache.put(key, value);
    }

    @Nullable
    public synchronized V get(K key) {
        return cache.get(key);
    }

    /**
     * Get a value from the cache, or compute and cache it if not present.
     *
     * <p>This operation is atomic - the supplier function is only called once per key even under
     * concurrent access.
     *
     * @param key The cache key
     * @param supplier Function to compute the value if not in cache (takes no args, returns value)
     * @return The cached or newly computed value
     */
    public synchronized V getOrCompute(K key, Supplier<V> supplier) {
        V value = cache.get(key);
        if (value == null) {
            // Cache miss - compute the value
            value = supplier.get();
            cache.put(key, value);
        }
        return value;
    }

    public synchronized boolean containsKey(K key) {
        return cache.containsKey(key);
    }

    public synchronized void clear() {
        cache.clear();
    }

    public synchronized int size() {
        return cache.size();
    }
}
