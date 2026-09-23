package com.example.flashsale.service;

import com.example.flashsale.dto.FlashSaleProductDTO;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import static com.example.flashsale.config.RedisCacheConfig.NO_SYNC_CACHE;

/**
 * BIEN THE DUNG DE SO SANH: @Cacheable(sync = false) - KHONG dung cho production.
 * Ton tai de chung minh Cache Stampede: N request dong thoi cung miss mot key se tao ra
 * N truy van Database.
 */
@Service
public class StampedeDemoService {

    private final ProductLoader productLoader;

    public StampedeDemoService(ProductLoader productLoader) {
        this.productLoader = productLoader;
    }

    @Cacheable(cacheNames = NO_SYNC_CACHE, key = "#productId", sync = false)
    public FlashSaleProductDTO getProductById(Long productId) {
        return productLoader.load(productId);
    }
}
