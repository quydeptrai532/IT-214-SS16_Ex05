package com.example.flashsale.service;

import com.example.flashsale.dto.FlashSaleProductDTO;
import com.example.flashsale.exception.ProductNotFoundException;
import com.example.flashsale.exception.ServiceBusyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Luong doc chinh: di qua cache, va co FALLBACK TUONG MINH khi cache/Redis loi.
 *
 * Vi sao phai bat loi tuong minh thay vi chi tin vao CacheErrorHandler?
 * Vi tren Spring Data Redis 4.x, duong doc voi sync = true co the nem RedisConnectionFailureException
 * ra ngoai (da kiem chung thuc te). Bat loi ngay tai day dam bao hanh vi giong nhau tren moi phien ban,
 * va la diem de dem so lan phai chuyen sang che do suy giam.
 */
@Service
public class FlashSaleProductService {

    private static final Logger log = LoggerFactory.getLogger(FlashSaleProductService.class);

    private final CachedProductReader cachedProductReader;
    private final ProductLoader productLoader;
    private final AtomicLong degradedModeFallbacks = new AtomicLong();

    public FlashSaleProductService(CachedProductReader cachedProductReader, ProductLoader productLoader) {
        this.cachedProductReader = cachedProductReader;
        this.productLoader = productLoader;
    }

    public FlashSaleProductDTO getProductById(Long productId) {
        try {
            return cachedProductReader.read(productId);
        } catch (ServiceBusyException | ProductNotFoundException businessError) {
            throw businessError;
        } catch (RuntimeException cacheError) {
            degradedModeFallbacks.incrementAndGet();
            log.warn("[DegradedMode] Cache/Redis loi ({}). Chuyen sang doc truc tiep qua ProductLoader "
                    + "(RateLimiter se bao ve Database).", cacheError.getClass().getSimpleName());
            return productLoader.load(productId);
        }
    }

    public long degradedModeFallbacks() {
        return degradedModeFallbacks.get();
    }

    public void resetCounters() {
        degradedModeFallbacks.set(0);
    }
}
