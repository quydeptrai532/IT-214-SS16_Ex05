package com.example.flashsale;

import com.example.flashsale.repository.ProductRepository;
import com.example.flashsale.service.FlashSaleProductService;
import com.example.flashsale.service.LocalFallbackCache;
import com.example.flashsale.service.ProductLoader;
import com.example.flashsale.service.StampedeDemoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.example.flashsale.config.RedisCacheConfig.FLASH_SALE_CACHE;
import static com.example.flashsale.config.RedisCacheConfig.NO_SYNC_CACHE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CACHE STAMPEDE (Thundering Herd): N request dong thoi cung miss mot key.
 *
 * Rate Limiter duoc nang tran len 1000/chu ky de no KHONG anh huong phep do stampede;
 * lop bao ve duoc kiem chung rieng o day chinh la @Cacheable(sync = true).
 */
@SpringBootTest(properties = {
        "app.warmup.enabled=false",
        "app.db.query-delay-ms=300",
        "app.resilience.rate-limiter.limit-for-period=1000",
        "app.resilience.rate-limiter.refresh-period=10s"
})
class CacheStampedeTest {

    private static final Long PRODUCT_ID = 1001L;
    private static final int CONCURRENT_REQUESTS = 300;

    @Autowired
    private FlashSaleProductService flashSaleProductService;

    @Autowired
    private StampedeDemoService stampedeDemoService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductLoader productLoader;

    @Autowired
    private LocalFallbackCache localFallbackCache;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        productRepository.reset();
        productLoader.resetCounters();
        localFallbackCache.clear();
        for (long id = 1001L; id <= 1010L; id++) {
            CacheTestUtils.evictAndWait(cacheManager, FLASH_SALE_CACHE, id);
            CacheTestUtils.evictAndWait(cacheManager, NO_SYNC_CACHE, id);
        }
        assertThat(cacheManager.getCache(FLASH_SALE_CACHE).get(PRODUCT_ID)).isNull();
        assertThat(cacheManager.getCache(NO_SYNC_CACHE).get(PRODUCT_ID)).isNull();
    }

    private int fireConcurrentRequests(Runnable call, int count) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < count; index++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    call.run();
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int failures = 0;
            for (Future<?> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    failures++;
                }
            }
            return failures;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void syncTrue_300RequestDongThoi_chiMotLuotXuongDatabase() throws Exception {
        long dbBefore = productRepository.queryCount();

        int failures = fireConcurrentRequests(
                () -> flashSaleProductService.getProductById(PRODUCT_ID), CONCURRENT_REQUESTS);

        long dbCalls = productRepository.queryCount() - dbBefore;
        System.out.println(">>> [KB1 STAMPEDE - sync=true] " + CONCURRENT_REQUESTS + " request dong thoi"
                + " | so truy van DB = " + dbCalls + " | loi = " + failures);

        assertThat(failures).isZero();
        assertThat(dbCalls).as("sync=true phai gom 300 request thanh dung 1 truy van DB").isEqualTo(1);
    }

    @Test
    void syncFalse_voiCungTai_nhieuLuotXuongDatabase() throws Exception {
        long dbBefore = productRepository.queryCount();

        int failures = fireConcurrentRequests(
                () -> stampedeDemoService.getProductById(PRODUCT_ID), CONCURRENT_REQUESTS);

        long dbCalls = productRepository.queryCount() - dbBefore;
        System.out.println(">>> [KB2 STAMPEDE - sync=false] " + CONCURRENT_REQUESTS + " request dong thoi"
                + " | so truy van DB = " + dbCalls + " | loi = " + failures);

        assertThat(failures).isZero();
        assertThat(dbCalls)
                .as("Khong co sync=true thi N request tao ra N truy van DB - day chinh la Cache Stampede")
                .isGreaterThan(1);
    }

    @Test
    void syncTrue_lanDocThuHaiSauKhiCacheDaAm_thiKhongChamDb() throws Exception {
        flashSaleProductService.getProductById(PRODUCT_ID);
        long dbAfterFirst = productRepository.queryCount();

        int failures = fireConcurrentRequests(
                () -> flashSaleProductService.getProductById(PRODUCT_ID), CONCURRENT_REQUESTS);

        System.out.println(">>> [KB3 CACHE HIT] " + CONCURRENT_REQUESTS + " request khi cache da am"
                + " | so truy van DB tang them = " + (productRepository.queryCount() - dbAfterFirst)
                + " | loi = " + failures);

        assertThat(failures).isZero();
        assertThat(productRepository.queryCount() - dbAfterFirst).isZero();
    }
}
