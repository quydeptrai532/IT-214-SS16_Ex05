package com.example.flashsale;

import com.example.flashsale.dto.FlashSaleProductDTO;
import com.example.flashsale.exception.ServiceBusyException;
import com.example.flashsale.repository.ProductRepository;
import com.example.flashsale.service.FlashSaleProductService;
import com.example.flashsale.service.LocalFallbackCache;
import com.example.flashsale.service.ProductLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DEGRADED MODE: Redis sap hoan toan.
 *
 * Mo phong bang cach tro toi cong 6399 (khong co gi lang nghe) - KHONG dung Redis that cua may.
 *
 * Rate Limiter duoc dat limit-for-period = 1 va refresh-period = 60s de phep do TAT DINH:
 * sau khi dung het 1 ve duy nhat, moi request tiep theo chac chan bi chan xuong DB.
 */
@SpringBootTest(properties = {
        "spring.data.redis.port=6399",
        "spring.data.redis.connect-timeout=300ms",
        "spring.data.redis.timeout=300ms",
        "app.warmup.enabled=false",
        "app.db.query-delay-ms=0",
        "app.resilience.rate-limiter.limit-for-period=1",
        "app.resilience.rate-limiter.refresh-period=60s",
        "app.local-fallback.ttl=5m",
        "logging.level.io.netty=OFF",
        "logging.level.io.lettuce=OFF"
})
class RedisDownDegradedTest {

    private static final Long PRODUCT_ID = 1001L;
    private static final int REQUESTS = 200;

    @Autowired
    private FlashSaleProductService flashSaleProductService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductLoader productLoader;

    @Autowired
    private LocalFallbackCache localFallbackCache;

    @BeforeEach
    void setUp() {
        productRepository.reset();
        productLoader.resetCounters();
        localFallbackCache.clear();
    }

    @Test
    void redisChet_rateLimiterChanDb_vaPhucVuTuCacheCucBo() throws Exception {
        // Mo phong "da co ban sao gan nhat trong cache cuc bo" (nhu sau mot lan doc thanh cong truoc do).
        // Lam san nhu vay de test khong phu thuoc vao viec con ve xuong DB hay khong.
        localFallbackCache.put(PRODUCT_ID, new FlashSaleProductDTO(
                PRODUCT_ID, "Flash Sale Item #1001", 1_099_000L, 500, true));

        long dbBefore = productRepository.queryCount();

        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch ready = new CountDownLatch(REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        int failures = 0;
        try {
            for (int index = 0; index < REQUESTS; index++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    flashSaleProductService.getProductById(PRODUCT_ID);
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    failures++;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        System.out.println(">>> [KB4 REDIS DOWN] " + REQUESTS + " request dong thoi khi Redis chet:"
                + " | loi = " + failures
                + " | truy van DB = " + (productRepository.queryCount() - dbBefore)
                + " | bi Rate Limiter chan = " + productLoader.rejectedByRateLimiterCount()
                + " | phuc vu tu cache cuc bo = " + productLoader.servedFromLocalCacheCount()
                + " | so lan chuyen sang degraded mode = " + flashSaleProductService.degradedModeFallbacks());

        assertThat(failures).as("Khong request nao bi loi - he thong khong chet chum").isZero();
        assertThat(productRepository.queryCount() - dbBefore)
                .as("Rate Limiter chan toan bo request xuong DB").isZero();
        assertThat(productLoader.rejectedByRateLimiterCount()).isEqualTo(REQUESTS);
        assertThat(productLoader.servedFromLocalCacheCount()).isEqualTo(REQUESTS);
        // Khong phai moi request deu di qua nhanh catch tuong minh cua FlashSaleProductService:
        // mot so duoc Spring CacheErrorHandler bat va roi thang xuong ProductLoader.
        // Ca hai duong deu ket thuc o ProductLoader -> cho nay chi can xac nhan co it nhat mot lan.
        assertThat(flashSaleProductService.degradedModeFallbacks()).isGreaterThan(0);
    }

    @Test
    void redisChet_hetVeVaKhongConBanSaoCucBo_thiTraVeServiceBusy() {
        try {
            flashSaleProductService.getProductById(PRODUCT_ID);
        } catch (ServiceBusyException ignored) {
            // ve co the da bi dung tu test truoc - khong anh huong ket luan
        }
        localFallbackCache.clear();

        assertThatThrownBy(() -> flashSaleProductService.getProductById(PRODUCT_ID))
                .isInstanceOf(ServiceBusyException.class);

        System.out.println(">>> [KB5 REDIS DOWN] Het ve + khong con ban sao cuc bo -> ServiceBusyException (API tra 429)");
    }
}
