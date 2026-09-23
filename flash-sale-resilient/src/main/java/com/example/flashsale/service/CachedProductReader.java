package com.example.flashsale.service;

import com.example.flashsale.dto.FlashSaleProductDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import static com.example.flashsale.config.RedisCacheConfig.FLASH_SALE_CACHE;

/**
 * Lop doc co cache. sync = true + RedisCacheWriter o che do LOCKING se gom N request dong thoi
 * thanh DUNG MOT truy van Database (chong Cache Stampede / Thundering Herd).
 */
@Service
public class CachedProductReader {

    private static final Logger log = LoggerFactory.getLogger(CachedProductReader.class);

    private final ProductLoader productLoader;

    public CachedProductReader(ProductLoader productLoader) {
        this.productLoader = productLoader;
    }

    @Cacheable(cacheNames = FLASH_SALE_CACHE, key = "#productId", sync = true)
    public FlashSaleProductDTO read(Long productId) {
        log.info("[FlashSale] CACHE MISS cho id={} -> xuong DB (co RateLimiter bao ve)", productId);
        return productLoader.load(productId);
    }
}
