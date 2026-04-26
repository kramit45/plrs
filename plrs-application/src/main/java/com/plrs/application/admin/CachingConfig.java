package com.plrs.application.admin;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Enables Spring's {@code @Cacheable} / {@code @CacheEvict} support
 * with a process-local {@link ConcurrentMapCacheManager}. Single-node
 * Iter 4 deploy; multi-node coordination via Redis is an Iter 5
 * configuration swap.
 */
@Configuration
@EnableCaching
public class CachingConfig {

    @Bean
    public SimpleCacheManager cacheManager() {
        SimpleCacheManager mgr = new SimpleCacheManager();
        mgr.setCaches(
                java.util.List.of(
                        new ConcurrentMapCacheManager(ConfigParamService.CACHE_NAME)
                                .getCache(ConfigParamService.CACHE_NAME)));
        return mgr;
    }
}
