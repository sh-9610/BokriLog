package com.bokrilog.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Story 3.9: 백업 기록 표는 추가 전용이고 SQL은 한 곳에 있다({@code OperatorAuditTest}의 원장 검사와 같은 관용).
 *
 * <p>운영 소스를 훑는다. 표를 SQL로 다루는 운영 클래스는 {@link BackupEventRepository} 하나이고, 그 파일에도 운영 소스 어디에도 그
 * 표를 고치거나 지우거나 비우는 문장이 없다. migration은 운영 소스가 아니라 이 검사 밖이다(표를 만드는 V24 하나뿐이다).
 */
class BackupEventRepositoryTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java");

    private static final Path REPOSITORY =
            MAIN_SOURCES.resolve("com/bokrilog/operations/internal/BackupEventRepository.java");

    private static final String TABLE = "operations_backup_event";

    private static final Pattern SQL_NAMING =
            Pattern.compile("(?:INTO|FROM|JOIN)\\s+" + TABLE + "\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern MUTATION = Pattern.compile(
            "\\b(?:UPDATE\\s+(?:ONLY\\s+)?" + TABLE + "\\b|DELETE\\s+FROM\\s+(?:ONLY\\s+)?" + TABLE + "\\b"
                    + "|TRUNCATE\\b[^;\"]*\\b" + TABLE + "\\b)",
            Pattern.CASE_INSENSITIVE);

    @Test
    void 표를_SQL로_다루는_운영_클래스는_repository_하나다() throws Exception {
        List<Path> naming = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                // SQL 조각(문자열 안의 INSERT·FROM·JOIN)만 센다. 문서 주석이 표 이름을 언급하는 것은 SQL이 아니다.
                if (SQL_NAMING.matcher(source).find()) {
                    naming.add(file.getFileName());
                }
            }
        }
        assertThat(naming).containsExactly(REPOSITORY.getFileName());
    }

    @Test
    void repository에는_INSERT와_SELECT뿐이고_운영_소스_어디에도_수정_문장이_없다() throws Exception {
        String source = Files.readString(REPOSITORY, StandardCharsets.UTF_8);
        assertThat(count(source, "UPDATE")).as("UPDATE 문장 수").isZero();
        assertThat(count(source, "DELETE")).as("DELETE 문장 수").isZero();
        assertThat(count(source, "TRUNCATE")).isZero();
        assertThat(source).contains("INSERT INTO " + TABLE);

        List<String> mutations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = MUTATION.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    mutations.add(file.getFileName() + ":" + matcher.group());
                }
            }
        }
        assertThat(mutations).isEmpty();
    }

    private static int count(String source, String word) {
        Matcher matcher = Pattern.compile("\\b" + word + "\\b").matcher(source);
        int found = 0;
        while (matcher.find()) {
            found++;
        }
        return found;
    }
}
