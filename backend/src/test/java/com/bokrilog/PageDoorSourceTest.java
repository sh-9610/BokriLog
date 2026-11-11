package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 시험 소스와 시험 resources({@code src/test})에 PostgreSQL 잠들기 함수(이름은 {@link #SLEEP}, {@code _for}·{@code _until}
 * 변형 포함)가 다시 생기지 않게 막는다 — 이 파일도 그 이름을 이어 붙여서만 쓴다. 대소문자를 가리지 않는다({@code AdTestBaseTest}의 관용, Gradle 시험의 working dir은 {@code backend}).
 * 페이지 저장 문의 objid({@link PlanSyncTestBase.PageDoorKey})가 서로 다른지도 본다. Spring을 띄우지 않는다.
 *
 * <p>수집 중 lease 상실·수집 중 명령의 순서는 {@link PlanSyncTestBase}의 페이지 저장 문(만료형·지연형)이 잠금과 작업 행
 * 관측으로 고정한다(deferred 177).
 */
class PageDoorSourceTest {

    /** 찾는 함수 이름(소문자). 이 파일이 걸리지 않게 이어 붙인다. */
    private static final String SLEEP = "pg_" + "sleep";

    /** 훑는 파일 확장자 — 시험 소스와 SQL·설정 resources. */
    private static final Set<String> SCANNED = Set.of("java", "sql", "yml", "yaml", "properties", "xml", "json");

    @Test
    void 시험_소스와_resources에_DB_잠들기_함수가_없다() throws IOException {
        Path root = Path.of("src/test");
        assertThat(root.resolve("java")).isDirectory();
        List<String> holders;
        try (Stream<Path> files = Files.walk(root)) {
            holders = files.filter(Files::isRegularFile).filter(PageDoorSourceTest::scanned)
                    .filter(path -> read(path).toLowerCase(Locale.ROOT).contains(SLEEP))
                    .map(path -> root.relativize(path).toString())
                    .toList();
        }
        assertThat(holders).as("페이지 저장 문(PlanSyncTestBase)으로 순서를 고정한다").isEmpty();
    }

    @Test
    void 페이지_저장_문의_objid는_서로_다르다() {
        var objects = Arrays.stream(PlanSyncTestBase.PageDoorKey.values()).map(key -> key.object).toList();
        assertThat(objects).doesNotHaveDuplicates().allSatisfy(object -> assertThat(object).isPositive());
    }

    private static boolean scanned(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && SCANNED.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(path.toString(), failure);
        }
    }
}
