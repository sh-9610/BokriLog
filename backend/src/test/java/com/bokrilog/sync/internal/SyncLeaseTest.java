package com.bokrilog.sync.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * lease 수명의 SQL 조각은 한 곳이다. 값을 바꾸면 첫 시험이, 저장소가 리터럴을 다시 쓰면 둘째 시험이 먼저 깨진다 — 둘째는
 * 운영 소스를 직접 훑는다(Gradle 시험의 working dir은 {@code backend}).
 */
class SyncLeaseTest {

    @Test
    void lease_수명은_30초다() {
        assertThat(SyncLease.SQL_INTERVAL).isEqualTo("interval '30 seconds'");
    }

    @Test
    void 운영_소스에서_lease_interval_리터럴은_SyncLease에만_있다() throws IOException {
        Path root = Path.of("src/main/java");
        assertThat(root).isDirectory();
        List<Path> holders;
        try (Stream<Path> files = Files.walk(root)) {
            holders = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> read(path).contains(SyncLease.SQL_INTERVAL))
                    .toList();
        }
        assertThat(holders).extracting(path -> path.getFileName().toString()).containsExactly("SyncLease.java");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(path.toString(), failure);
        }
    }
}
