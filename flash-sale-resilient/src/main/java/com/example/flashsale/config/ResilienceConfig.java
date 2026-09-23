package com.example.flashsale.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Dung API LAP TRINH cua Resilience4j (khong dung annotation) de:
 *  - khong phu thuoc AOP/AspectJ (tranh truong hop annotation "im lang khong chay"),
 *  - kiem thu duoc tat dinh, goi truc tiep trong test.
 */
@Configuration
public class ResilienceConfig {

    public static final String DB_GUARD_RATE_LIMITER = "dbGuardRateLimiter";
    public static final String DB_CIRCUIT_BREAKER = "dbCircuitBreaker";

    /** Tran so luot duoc phep xuong Database trong moi chu ky lam moi. */
    @Bean
    public RateLimiter dbGuardRateLimiter(
            @Value("${app.resilience.rate-limiter.limit-for-period:50}") int limitForPeriod,
            @Value("${app.resilience.rate-limiter.refresh-period:1s}") Duration refreshPeriod,
            @Value("${app.resilience.rate-limiter.timeout:0ms}") Duration timeoutDuration) {
        return RateLimiter.of(DB_GUARD_RATE_LIMITER, RateLimiterConfig.custom()
                .limitForPeriod(limitForPeriod)
                .limitRefreshPeriod(refreshPeriod)
                .timeoutDuration(timeoutDuration)
                .build());
    }

    /** Khi DB bat dau loi lien tuc thi mo mach, khong day request xuong nua (fail fast). */
    @Bean
    public CircuitBreaker dbCircuitBreaker(
            @Value("${app.resilience.circuit-breaker.failure-rate-threshold:60}") float failureRateThreshold,
            @Value("${app.resilience.circuit-breaker.sliding-window-size:20}") int slidingWindowSize,
            @Value("${app.resilience.circuit-breaker.wait-duration-in-open-state:5s}") Duration waitDuration) {
        return CircuitBreaker.of(DB_CIRCUIT_BREAKER, CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(slidingWindowSize)
                .waitDurationInOpenState(waitDuration)
                .build());
    }
}
