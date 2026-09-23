package com.example.flashsale.controller;

import com.example.flashsale.dto.FlashSaleProductDTO;
import com.example.flashsale.repository.ProductRepository;
import com.example.flashsale.service.CacheWarmupRunner;
import com.example.flashsale.service.FlashSaleProductService;
import com.example.flashsale.service.LocalFallbackCache;
import com.example.flashsale.service.ProductLoader;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/flash-sale")
public class FlashSaleController {

    private final FlashSaleProductService flashSaleProductService;
    private final CacheWarmupRunner cacheWarmupRunner;
    private final ProductLoader productLoader;
    private final ProductRepository productRepository;
    private final LocalFallbackCache localFallbackCache;

    public FlashSaleController(FlashSaleProductService flashSaleProductService,
                               CacheWarmupRunner cacheWarmupRunner,
                               ProductLoader productLoader,
                               ProductRepository productRepository,
                               LocalFallbackCache localFallbackCache) {
        this.flashSaleProductService = flashSaleProductService;
        this.cacheWarmupRunner = cacheWarmupRunner;
        this.productLoader = productLoader;
        this.productRepository = productRepository;
        this.localFallbackCache = localFallbackCache;
    }

    @GetMapping("/products/{productId}")
    public ResponseEntity<FlashSaleProductDTO> getProduct(@PathVariable Long productId) {
        return ResponseEntity.ok(flashSaleProductService.getProductById(productId));
    }

    /** Chay lai warm-up bang tay (truong hop can nap lai truoc khi mo ban). */
    @PostMapping("/warmup")
    public ResponseEntity<CacheWarmupRunner.WarmupReport> warmup() {
        return ResponseEntity.ok(cacheWarmupRunner.warmUp());
    }

    @GetMapping("/diagnostics")
    public ResponseEntity<Diagnostics> diagnostics() {
        return ResponseEntity.ok(new Diagnostics(
                productRepository.queryCount(),
                productLoader.databaseCalls(),
                productLoader.servedFromLocalCacheCount(),
                productLoader.rejectedByRateLimiterCount(),
                localFallbackCache.size()));
    }

    /**
     * Ban do hieu nang: ban ra N request song song vao CUNG mot san pham va do do tre.
     * Dung de chung minh "co cache" nhanh hon "khong cache" bao nhieu lan.
     */
    @GetMapping("/benchmark")
    public ResponseEntity<BenchmarkReport> benchmark(@RequestParam Long productId,
                                                     @RequestParam(defaultValue = "200") int requests,
                                                     @RequestParam(defaultValue = "32") int threads) throws Exception {
        long dbBefore = productRepository.queryCount();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger errors = new AtomicInteger();
        try {
            List<Callable<Long>> tasks = new ArrayList<>();
            for (int index = 0; index < requests; index++) {
                tasks.add(() -> {
                    long startedAt = System.nanoTime();
                    try {
                        flashSaleProductService.getProductById(productId);
                    } catch (RuntimeException exception) {
                        errors.incrementAndGet();
                    }
                    return System.nanoTime() - startedAt;
                });
            }
            List<Future<Long>> futures = pool.invokeAll(tasks);
            long[] latencies = new long[futures.size()];
            for (int index = 0; index < futures.size(); index++) {
                latencies[index] = futures.get(index).get();
            }
            java.util.Arrays.sort(latencies);
            long total = 0;
            for (long latency : latencies) {
                total += latency;
            }
            long dbCalls = productRepository.queryCount() - dbBefore;
            return ResponseEntity.ok(new BenchmarkReport(requests, threads,
                    round(total / (double) requests), round(percentile(latencies, 0.95)),
                    round(percentile(latencies, 0.99)), dbCalls, errors.get()));
        } finally {
            pool.shutdownNow();
        }
    }

    private double percentile(long[] sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))] / 1_000_000.0;
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    public record Diagnostics(long databaseQueries, long loaderDatabaseCalls, long servedFromLocalCache,
                              long rejectedByRateLimiter, long localCacheSize) {
    }

    public record BenchmarkReport(int requests, int threads, double avgLatencyMs, double p95LatencyMs,
                                  double p99LatencyMs, long databaseQueries, int errors) {
    }
}
