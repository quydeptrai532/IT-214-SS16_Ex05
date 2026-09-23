package com.example.flashsale;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * Tien ich cho test.
 *
 * Ly do ton tai: cac thao tac GHI len Redis cache (put/evict/clear) trong Spring Data Redis 4.x
 * khong hien ket qua NGAY LAP TUC (va cung khong nem loi khi Redis gap su co). Vi vay test khong the
 * evict roi doc lai ngay ma ket luan duoc - phai CHO den khi key thuc su bien mat.
 */
final class CacheTestUtils {

    private static final int MAX_ATTEMPTS = 50;
    private static final long DELAY_MS = 40;

    private CacheTestUtils() {
    }

    static void evictAndWait(CacheManager cacheManager, String cacheName, long id) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            return;
        }
        cache.evict(id);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (cache.get(id) == null) {
                return;
            }
            sleep(DELAY_MS);
        }
        throw new IllegalStateException("Khong the xoa key " + id + " khoi cache '" + cacheName + "'");
    }

    static void evictRangeAndWait(CacheManager cacheManager, String cacheName, long from, long to) {
        for (long id = from; id <= to; id++) {
            evictAndWait(cacheManager, cacheName, id);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
