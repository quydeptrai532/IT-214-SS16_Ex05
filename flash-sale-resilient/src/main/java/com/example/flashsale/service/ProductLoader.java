package com.example.flashsale.service;

import com.example.flashsale.dto.FlashSaleProductDTO;
import com.example.flashsale.exception.ProductNotFoundException;
import com.example.flashsale.exception.ServiceBusyException;
import com.example.flashsale.repository.ProductRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Cua duy nhat duoc phep di xuong Database. Moi request muon cham DB deu phai qua day,
 * va phai xin duoc 1 "ve" tu Rate Limiter.
 *
 * Day la lop bao ve cuoi cung cua he thong: du Redis co sap hoan toan thi DB van khong bi
 * "danh sap" vi so luot truy van trong moi giay bi chan tran.
 */
@Service
public class ProductLoader {

    private static final Logger log = LoggerFactory.getLogger(ProductLoader.class);

    private final ProductRepository productRepository;
    private final LocalFallbackCache localFallbackCache;
    private final RateLimiter rateLimiter;
    private final CircuitBreaker circuitBreaker;

    private final AtomicLong databaseCalls = new AtomicLong();
    private final AtomicLong servedFromLocalCache = new AtomicLong();
    private final AtomicLong rejectedByRateLimiter = new AtomicLong();

    public ProductLoader(ProductRepository productRepository,
                         LocalFallbackCache localFallbackCache,
                         RateLimiter dbGuardRateLimiter,
                         CircuitBreaker dbCircuitBreaker) {
        this.productRepository = productRepository;
        this.localFallbackCache = localFallbackCache;
        this.rateLimiter = dbGuardRateLimiter;
        this.circuitBreaker = dbCircuitBreaker;
    }

    public FlashSaleProductDTO load(Long productId) {
        if (!rateLimiter.acquirePermission()) {
            rejectedByRateLimiter.incrementAndGet();
            log.warn("[RateLimiter] Da chan request id={} (tran {} luot/chu ky). Phuc vu bang cache cuc bo neu co.",
                    productId, rateLimiter.getRateLimiterConfig().getLimitForPeriod());
            return serveFromLocalCache(productId, null);
        }
        try {
            FlashSaleProductDTO dto = circuitBreaker.executeSupplier(() -> {
                databaseCalls.incrementAndGet();
                return FlashSaleProductDTO.from(productRepository.findById(productId)
                        .orElseThrow(() -> new ProductNotFoundException(productId)));
            });
            localFallbackCache.put(productId, dto);
            return dto;
        } catch (ProductNotFoundException notFound) {
            throw notFound;
        } catch (RuntimeException exception) {
            log.warn("[DegradedMode] Truy van DB that bai ({}). Thu phuc vu bang cache cuc bo.",
                    exception.getClass().getSimpleName());
            return serveFromLocalCache(productId, exception);
        }
    }

    private FlashSaleProductDTO serveFromLocalCache(Long productId, RuntimeException original) {
        return localFallbackCache.get(productId).map(dto -> {
            servedFromLocalCache.incrementAndGet();
            return dto;
        }).orElseThrow(() -> original instanceof RequestNotPermitted
                ? new ServiceBusyException(productId)
                : new ServiceBusyException(productId));
    }

    public long databaseCalls() {
        return databaseCalls.get();
    }

    public long servedFromLocalCacheCount() {
        return servedFromLocalCache.get();
    }

    public long rejectedByRateLimiterCount() {
        return rejectedByRateLimiter.get();
    }

    public void resetCounters() {
        databaseCalls.set(0);
        servedFromLocalCache.set(0);
        rejectedByRateLimiter.set(0);
    }
}
