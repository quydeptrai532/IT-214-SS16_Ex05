package com.example.flashsale.service;

import com.example.flashsale.dto.FlashSaleProductDTO;
import com.example.flashsale.model.Product;
import com.example.flashsale.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.example.flashsale.config.RedisCacheConfig.FLASH_SALE_CACHE;

/**
 * CHIEN LUOC CACHE WARM-UP: chay ngay khi ung dung khoi dong, nap san du lieu Flash Sale vao Redis
 * TRUOC khi su kien bat dau.
 *
 * Vi sao can? Neu de "Cold Start", hang nghin request dau tien deu cache miss va dong loat dap xuong
 * Database ngay tai thoi diem mo ban -> sap he thong dung luc dong khach nhat.
 *
 * Vi sao phai XAC MINH? Vi cac thao tac ghi len cache trong Spring Data Redis 4.x khong nem loi khi
 * Redis gap su co (that bai am tham - xem Ex04). Warm-up ma khong kiem chung thi co the "bao thanh cong"
 * trong khi cache van rong.
 */
@Component
public class CacheWarmupRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CacheWarmupRunner.class);

    private static final int VERIFY_ATTEMPTS = 5;
    private static final long VERIFY_DELAY_MS = 20;

    private final ProductRepository productRepository;
    private final CacheManager cacheManager;
    private final boolean enabled;

    public CacheWarmupRunner(ProductRepository productRepository,
                             CacheManager cacheManager,
                             @Value("${app.warmup.enabled:true}") boolean enabled) {
        this.productRepository = productRepository;
        this.cacheManager = cacheManager;
        this.enabled = enabled;
    }

    @Override
    public void run(String... args) {
        if (!enabled) {
            log.info("[Warmup] Warm-up dang TAT (app.warmup.enabled=false) - bo qua");
            return;
        }
        warmUp();
    }

    public WarmupReport warmUp() {
        long startedAt = System.nanoTime();
        Cache cache = cacheManager.getCache(FLASH_SALE_CACHE);
        if (cache == null) {
            log.error("[Warmup] Khong lay duoc cache '{}' -> bo qua warm-up", FLASH_SALE_CACHE);
            return new WarmupReport(0, 0, 0);
        }

        List<Product> flashSaleProducts = productRepository.findAllFlashSale();
        int attempted = 0;
        for (Product product : flashSaleProducts) {
            attempted++;
            cache.put(product.getId(), FlashSaleProductDTO.from(product));
        }

        // Xac minh SAU khi nap xong: thao tac ghi len Redis co the chua kip hoan tat, nen phai doc lai
        // va thu lai vai lan truoc khi ket luan la that bai.
        int verified = 0;
        for (Product product : flashSaleProducts) {
            if (isPresentInCache(cache, product.getId())) {
                verified++;
            } else {
                log.error("[Warmup] Key {} KHONG nam duoc trong Redis sau khi thu lai nhieu lan", product.getId());
            }
        }

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        log.info("[Warmup] Nap xong {} / {} san pham Flash Sale vao Redis trong {} ms "
                        + "(da xac minh {} key co that su nam trong cache)",
                verified, attempted, elapsedMs, verified);
        return new WarmupReport(attempted, verified, elapsedMs);
    }

    private boolean isPresentInCache(Cache cache, Long productId) {
        for (int attempt = 1; attempt <= VERIFY_ATTEMPTS; attempt++) {
            if (cache.get(productId) != null) {
                return true;
            }
            sleep(VERIFY_DELAY_MS);
        }
        return false;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    public record WarmupReport(int attempted, int verified, long elapsedMs) {
    }
}
