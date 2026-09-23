package com.example.flashsale.repository;

import com.example.flashsale.model.Product;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Database mo phong - co THEM DO TRE truy van de do luong duoc chenh lech "co cache" / "khong cache".
 * Moi truy van deu duoc dem -> dung lam bang chung cho bang so sanh hieu nang.
 */
@Repository
public class InMemoryProductRepository implements ProductRepository {

    private final Map<Long, Product> database = new ConcurrentHashMap<>();
    private final AtomicLong queryCount = new AtomicLong();

    private volatile long queryDelayMs;

    public InMemoryProductRepository(@Value("${app.db.query-delay-ms:120}") long queryDelayMs) {
        this.queryDelayMs = queryDelayMs;
        seed();
    }

    private void seed() {
        for (long id = 1001L; id <= 1100L; id++) {
            database.put(id, new Product(id, "Flash Sale Item #" + id, 99_000L + (id * 1000), 500, true));
        }
        for (long id = 2001L; id <= 2020L; id++) {
            database.put(id, new Product(id, "Thuong Item #" + id, 1_500_000L, 200, false));
        }
    }

    @Override
    public Optional<Product> findById(Long id) {
        queryCount.incrementAndGet();
        simulateDatabaseLatency();
        return Optional.ofNullable(database.get(id)).map(Product::copy);
    }

    @Override
    public List<Product> findAllFlashSale() {
        queryCount.incrementAndGet();
        simulateDatabaseLatency();
        return database.values().stream()
                .filter(Product::isFlashSale)
                .map(Product::copy)
                .sorted(Comparator.comparing(Product::getId))
                .toList();
    }

    @Override
    public long queryCount() {
        return queryCount.get();
    }

    @Override
    public long flashSaleSize() {
        return database.values().stream().filter(Product::isFlashSale).count();
    }

    @Override
    public void setQueryDelay(Duration delay) {
        this.queryDelayMs = delay.toMillis();
    }

    @Override
    public void reset() {
        queryCount.set(0);
    }

    private void simulateDatabaseLatency() {
        if (queryDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(queryDelayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
