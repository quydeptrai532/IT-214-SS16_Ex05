package com.example.flashsale;

import com.example.flashsale.repository.ProductRepository;
import com.example.flashsale.service.FlashSaleProductService;
import com.example.flashsale.service.LocalFallbackCache;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.flashsale.config.RedisCacheConfig.FLASH_SALE_CACHE;
import static org.assertj.core.api.Assertions.assertThat;

/** Do luong de lam bang chung cho bang so sanh hieu nang TRUOC / SAU khi co cache. */
@SpringBootTest(properties = {
        "app.warmup.enabled=false",
        "app.db.query-delay-ms=120",
        "app.resilience.rate-limiter.limit-for-period=1000000",
        "app.resilience.rate-limiter.refresh-period=10s"
})
class FlashSalePerformanceTest {

    @Autowired
    private FlashSaleProductService flashSaleProductService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private LocalFallbackCache localFallbackCache;

    @Autowired
    private CacheManager cacheManager;

    private void evict(Long productId) {
        CacheTestUtils.evictAndWait(cacheManager, FLASH_SALE_CACHE, productId);
    }

    @Test
    void coldVsWarm_doTreGiamManhSauLanDocDauTien() {
        Long productId = 1005L;
        localFallbackCache.clear();
        evict(productId);

        long coldStart = System.nanoTime();
        flashSaleProductService.getProductById(productId);
        double coldMs = (System.nanoTime() - coldStart) / 1_000_000.0;

        int warmRounds = 200;
        long warmStart = System.nanoTime();
        for (int index = 0; index < warmRounds; index++) {
            flashSaleProductService.getProductById(productId);
        }
        double warmAvgMs = (System.nanoTime() - warmStart) / 1_000_000.0 / warmRounds;

        double speedUp = coldMs / Math.max(warmAvgMs, 0.0001);
        System.out.println(">>> [KB8 HIEU NANG] Cold (cache miss, xuong DB co delay 120ms) = "
                + Math.round(coldMs * 100) / 100.0 + " ms"
                + " | Warm (cache hit, lay tu Redis) = " + Math.round(warmAvgMs * 10000) / 10000.0 + " ms"
                + " | nhanh hon " + Math.round(speedUp) + " lan");

        assertThat(warmAvgMs).isLessThan(coldMs / 10.0);
    }

    @Test
    void muoiNghinRequestCungMotSanPham_tatCaCacheHit_vaKhongChamDatabase() throws Exception {
        Long productId = 1006L;
        localFallbackCache.clear();
        evict(productId);
        flashSaleProductService.getProductById(productId);

        long dbBefore = productRepository.queryCount();
        int requests = 10_000;
        int threads = 64;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger errors = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>(requests);
        long startedAt = System.nanoTime();
        try {
            for (int index = 0; index < requests; index++) {
                futures.add(pool.submit(() -> {
                    try {
                        flashSaleProductService.getProductById(productId);
                    } catch (RuntimeException exception) {
                        errors.incrementAndGet();
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        double wallMs = (System.nanoTime() - startedAt) / 1_000_000.0;
        long dbCalls = productRepository.queryCount() - dbBefore;

        double averageMs = wallMs / requests;
        System.out.println(">>> [KB9 10.000 REQUEST] " + requests + " request / " + threads + " luong"
                + " | tong thoi gian = " + Math.round(wallMs) + " ms"
                + " | trung binh = " + (Math.round(averageMs * 1000) / 1000.0) + " ms/request"
                + " | throughput = " + Math.round(requests * 1000.0 / wallMs) + " request/giay"
                + " | truy van DB = " + dbCalls + " | loi = " + errors.get());

        assertThat(errors.get()).isZero();
        assertThat(dbCalls).as("10.000 request deu la cache hit -> Database khong bi cham").isZero();
    }
}
