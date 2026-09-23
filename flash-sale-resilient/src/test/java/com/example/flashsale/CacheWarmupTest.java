package com.example.flashsale;

import com.example.flashsale.repository.ProductRepository;
import com.example.flashsale.service.CacheWarmupRunner;
import com.example.flashsale.service.FlashSaleProductService;
import com.example.flashsale.service.LocalFallbackCache;
import com.example.flashsale.service.ProductLoader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import static com.example.flashsale.config.RedisCacheConfig.FLASH_SALE_CACHE;
import static org.assertj.core.api.Assertions.assertThat;

/** CHONG COLD START: nap san du lieu Flash Sale vao Redis truoc khi su kien mo ban. */
@SpringBootTest(properties = {
        "app.warmup.enabled=false",
        "app.db.query-delay-ms=0",
        "app.resilience.rate-limiter.limit-for-period=100000",
        "app.resilience.rate-limiter.refresh-period=10s"
})
class CacheWarmupTest {

    @Autowired
    private CacheWarmupRunner cacheWarmupRunner;

    @Autowired
    private FlashSaleProductService flashSaleProductService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductLoader productLoader;

    @Autowired
    private LocalFallbackCache localFallbackCache;

    @Autowired
    private CacheManager cacheManager;

    private Cache flashSaleCache() {
        return cacheManager.getCache(FLASH_SALE_CACHE);
    }

    private long countCachedFlashSaleProducts() {
        long count = 0;
        for (long id = 1001L; id <= 1100L; id++) {
            if (flashSaleCache().get(id) != null) {
                count++;
            }
        }
        return count;
    }

    private void clearFlashSaleCache() {
        for (long id = 1001L; id <= 1100L; id++) {
            CacheTestUtils.evictAndWait(cacheManager, FLASH_SALE_CACHE, id);
        }
    }

    @Test
    void commandLineRunner_napSanToanBoFlashSaleVaoRedisKhiKhoiDong() {
        clearFlashSaleCache();
        assertThat(countCachedFlashSaleProducts()).isZero();

        // Chinh phuong thuc ma Spring goi luc ung dung khoi dong (CommandLineRunner#run)
        CacheWarmupRunner.WarmupReport report = cacheWarmupRunner.warmUp();

        System.out.println(">>> [KB6 WARM-UP] Thu nap = " + report.attempted()
                + " | xac minh co trong Redis = " + report.verified()
                + " | thoi gian = " + report.elapsedMs() + " ms");

        assertThat(report.attempted()).isEqualTo((int) productRepository.flashSaleSize());
        assertThat(report.verified()).isEqualTo(report.attempted());
        assertThat(countCachedFlashSaleProducts()).isEqualTo(productRepository.flashSaleSize());
    }

    @Test
    void sauWarmUp_docToanBoFlashSale_khongChamToiDatabase() {
        cacheWarmupRunner.warmUp();
        productRepository.reset();
        productLoader.resetCounters();
        localFallbackCache.clear();

        for (long id = 1001L; id <= 1100L; id++) {
            assertThat(flashSaleProductService.getProductById(id)).isNotNull();
        }

        System.out.println(">>> [KB7 WARM-UP] Doc 100 san pham sau warm-up:"
                + " | truy van DB = " + productRepository.queryCount()
                + " | goi xuong DB qua ProductLoader = " + productLoader.databaseCalls());

        assertThat(productRepository.queryCount()).as("Warm-up tot thi khong con Cold Start").isZero();
    }
}
