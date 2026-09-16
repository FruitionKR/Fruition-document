package fruition.core.query;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import static org.junit.jupiter.api.Assertions.*;

class RedisAclContractTest {
    @Test
    void lettuceCommandsAndLuaWorkWithTerraformAcl() throws Exception {
        try (var redis = new GenericContainer<>("redis:7.0-alpine").withExposedPorts(6379)) {
            redis.start();
            var acl = Files.readString(Path.of("src/test/resources/redis-document.acl")).trim();
            var args = new java.util.ArrayList<>(List.of("redis-cli", "ACL", "SETUSER", "document", ">isolated-test-password"));
            args.addAll(List.of(acl.split(" ")));
            assertEquals("OK", redis.execInContainer(args.toArray(String[]::new)).getStdout().trim());
            var config = new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379));
            config.setUsername("document"); config.setPassword("isolated-test-password");
            var factory = new LettuceConnectionFactory(config); factory.afterPropertiesSet(); factory.start();
            try {
                var template = new StringRedisTemplate(factory);
                assertTrue(template.opsForValue().setIfAbsent("query:run:a", "running", Duration.ofMinutes(1)));
                assertEquals("running", template.opsForValue().get("query:run:a"));
                assertEquals(1L, template.opsForValue().increment("query:events-seq:a"));
                assertTrue(template.expire("query:events-seq:a", Duration.ofMinutes(1)));
                var lua = new DefaultRedisScript<Long>("redis.call('RPUSH',KEYS[1],ARGV[1]); redis.call('LTRIM',KEYS[1],-200,-1); redis.call('EXPIRE',KEYS[1],60); redis.call('PUBLISH',ARGV[2],ARGV[1]); return 1", Long.class);
                assertEquals(1L, template.execute(lua, List.of("query:events:a"), "event", "query-events"));
                assertEquals(List.of("event"), template.opsForList().range("query:events:a", 0, -1));
                assertThrows(RuntimeException.class, () -> template.opsForValue().get("oauth:exchange:secret"));
            } finally { factory.destroy(); }
        }
    }
}
