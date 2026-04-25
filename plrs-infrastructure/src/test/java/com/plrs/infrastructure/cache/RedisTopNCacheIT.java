package com.plrs.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.plrs.application.cache.TopNCache;
import com.plrs.domain.user.UserId;
import com.plrs.infrastructure.testsupport.RedisTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Integration test for {@link RedisTopNCache}. Drives the {@link TopNCache}
 * port (not the adapter class) so the Spring port→adapter wiring is
 * itself exercised on every run. The third test instantiates a fresh
 * adapter against a mock {@link StringRedisTemplate} that throws to
 * verify the swallow-and-log fallback.
 *
 * <p>Boot context uses a nested {@link RedisTopNCacheApp} application to
 * avoid the plrs-web reactor cycle; JDBC/JPA auto-configs are excluded
 * so the test only needs Redis.
 */
@SpringBootTest(classes = RedisTopNCacheIT.RedisTopNCacheApp.class)
class RedisTopNCacheIT extends RedisTestBase {

    @Autowired private TopNCache cache;
    @Autowired private StringRedisTemplate redis;

    @Test
    void invalidateDeletesKey() {
        UserId userId = UserId.newId();
        String key = RedisTopNCache.KEY_PREFIX + userId.value();
        redis.opsForValue().set(key, "[\"item-1\",\"item-2\"]");
        assertThat(redis.opsForValue().get(key)).isNotNull();

        cache.invalidate(userId);

        assertThat(redis.opsForValue().get(key)).isNull();
    }

    @Test
    void invalidateOnMissingKeyDoesNotThrow() {
        UserId userId = UserId.newId();
        // Pre-condition: key absent.
        assertThat(redis.opsForValue().get(RedisTopNCache.KEY_PREFIX + userId.value()))
                .isNull();

        assertThatCode(() -> cache.invalidate(userId)).doesNotThrowAnyException();
    }

    @Test
    void invalidateSwallowsRedisExceptions() {
        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        when(broken.delete(anyString()))
                .thenThrow(new DataAccessResourceFailureException("redis down"));
        TopNCache failing = new RedisTopNCache(broken);

        assertThatCode(() -> failing.invalidate(UserId.newId())).doesNotThrowAnyException();
    }

    @SpringBootApplication(
            exclude = {
                DataSourceAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                JpaRepositoriesAutoConfiguration.class
            })
    static class RedisTopNCacheApp {}
}
