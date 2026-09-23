package com.example.flashsale.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.SimpleCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
public class RedisCacheConfig implements CachingConfigurer {

    public static final String FLASH_SALE_CACHE = "flash-sale-products";
    public static final String NO_SYNC_CACHE = "flash-sale-no-sync";

    private static final Logger log = LoggerFactory.getLogger(RedisCacheConfig.class);

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                          @Value("${app.cache.flash-sale-ttl:2m}") Duration ttl) {
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()
                .prefixCacheNameWith("flashsale:")
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(GenericJacksonJsonRedisSerializer.builder()
                                .enableUnsafeDefaultTyping()
                                .build()));

        // QUAN TRONG: phai dung LOCKING cache writer thi @Cacheable(sync = true) moi thuc su khoa
        // cac luong lai voi nhau. Voi nonLockingRedisCacheWriter (mac dinh khi build tu
        // connectionFactory), sync = true KHONG chong duoc Cache Stampede.
        RedisCacheWriter cacheWriter = RedisCacheWriter.lockingRedisCacheWriter(connectionFactory);
        return RedisCacheManager.builder(cacheWriter)
                .cacheDefaults(base)
                .withCacheConfiguration(FLASH_SALE_CACHE, base)
                .withCacheConfiguration(NO_SYNC_CACHE, base)
                .build();
    }

    /**
     * Fail-open: loi cache KHONG duoc lam sap he thong.
     * Doc loi -> coi nhu cache miss, truy van thang DB (nhuong cho Rate Limiter bao ve DB).
     * Ghi/xoa loi -> ghi log roi bo qua.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new SimpleCacheErrorHandler() {

            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("[CacheFallback] Doc cache '{}' that bai (key={}) -> doc truc tiep DB. Nguyen nhan: {}",
                        cache.getName(), key, exception.getClass().getSimpleName());
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                log.warn("[CacheFallback] Ghi cache '{}' that bai (key={}) -> bo qua.", cache.getName(), key);
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                log.warn("[CacheFallback] Xoa cache '{}' that bai (key={}) -> bo qua.", cache.getName(), key);
            }
        };
    }
}
