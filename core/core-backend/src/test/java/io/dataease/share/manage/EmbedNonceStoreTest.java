package io.dataease.share.manage;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DuplicateKeyException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EmbedNonceStoreTest {
    @Test void desktopMigrationAndConcurrentNonceConsumption() throws Exception {
        Assumptions.assumeTrue(org.springframework.util.ClassUtils.isPresent("org.h2.Driver",getClass().getClassLoader()));
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:embed_nonce;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (var input = getClass().getResourceAsStream("/db/desktop/V2.10.27.1__embed_nonce.sql")) {
            assertNotNull(input);
            for (String sql : new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).split(";"))
                if (!sql.isBlank()) jdbc.execute(sql);
        }
        AtomicInteger accepted = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var jobs = new java.util.ArrayList<Future<?>>();
            for (int i=0;i<8;i++) jobs.add(executor.submit(()-> {
                try { jdbc.update("INSERT INTO de_embed_nonce (key_id, nonce, expires_at) VALUES (?, ?, ?)","test","single-use-nonce",1000L); accepted.incrementAndGet(); }
                catch (DuplicateKeyException expected) { }
            }));
            for (var job:jobs) job.get();
        }
        assertEquals(1,accepted.get());
        assertEquals(0,jdbc.update("DELETE FROM de_embed_nonce WHERE expires_at < ? LIMIT 100",999L));
        assertEquals(1,jdbc.update("DELETE FROM de_embed_nonce WHERE expires_at < ? LIMIT 100",1001L));
    }
}
