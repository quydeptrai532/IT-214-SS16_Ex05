package com.example.flashsale.service;

import com.example.flashsale.dto.FlashSaleProductDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cache cuc bo (L1) nam trong JVM cua tung instance.
 * Vai tro: khi Redis sap HOAC khi da cham tran Rate Limiter, van con mot ban sao gan nhat de phuc vu
 * khach thay vi tra loi loi -> day chinh la "degraded mode".
 */
@Component
public class LocalFallbackCache {

    private static final Logger log = LoggerFactory.getLogger(LocalFallbackCache.class);

    private record Entry(FlashSaleProductDTO value, Instant expiresAt) {
    }

    private final Map<Long, Entry> store = new ConcurrentHashMap<>();
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();

    private final Duration ttl;

    public LocalFallbackCache(@Value("${app.local-fallback.ttl:30s}") Duration ttl) {
        this.ttl = ttl;
    }

    public void put(Long productId, FlashSaleProductDTO value) {
        store.put(productId, new Entry(value, Instant.now().plus(ttl)));
    }

    public Optional<FlashSaleProductDTO> get(Long productId) {
        Entry entry = store.get(productId);
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
            missCount.incrementAndGet();
            return Optional.empty();
        }
        hitCount.incrementAndGet();
        return Optional.of(entry.value());
    }

    public long hitCount() {
        return hitCount.get();
    }

    public long missCount() {
        return missCount.get();
    }

    public long size() {
        return store.size();
    }

    public void clear() {
        store.clear();
        hitCount.set(0);
        missCount.set(0);
        log.info("[LocalCache] Da xoa toan bo cache cuc bo");
    }
}
