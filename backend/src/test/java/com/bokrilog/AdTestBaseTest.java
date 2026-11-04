package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 광고 시험 helper가 한 벌인지 본다 — 시험 소스({@code src/test/java})를 훑어 옮긴 helper 이름의 메서드 정의가 정해진 base 밖에
 * 다시 생기지 않게 막는다({@code ControllerSupportTest}의 관용, Gradle 시험의 working dir은 {@code backend}). Spring을 띄우지 않는다.
 *
 * <p>광고 helper는 {@link AdTestBase}에, 세 시험이 함께 쓰는 {@code concurrently}는 {@link OperatorTestBase}에 둔다. 훑는 범위는 그
 * base를 상속하는 시험(과 base 자신)뿐이다 — {@code grant}·{@code claim}처럼 흔한 이름을 광고와 무관한 시험까지 금지하지 않는다. 이름을
 * 바꾼 사본까지는 잡지 않는다 — 막는 것은 같은 이름의 재정의(가리기)로 base를 비켜 가는 일이다.
 */
class AdTestBaseTest {

    private static final String AD_BASE = "com/bokrilog/AdTestBase.java";
    private static final String OPERATOR_BASE = "com/bokrilog/OperatorTestBase.java";

    /** 광고 helper를 볼 수 있는 시험 — {@link AdTestBase}의 하위 클래스. */
    private static final Pattern AD_FAMILY = Pattern.compile("\\bextends\\s+AdTestBase\\b");

    /** {@code concurrently}를 볼 수 있는 시험 — {@link OperatorTestBase}나 {@link AdTestBase}의 하위 클래스. */
    private static final Pattern OPERATOR_FAMILY = Pattern.compile("\\bextends\\s+(?:OperatorTestBase|AdTestBase)\\b");

    /** helper 이름 → 정의가 있어야 하는 유일한 파일({@code src/test/java} 기준 경로). */
    private static final Map<String, String> HOLDERS = Map.of(
            "admitManual", AD_BASE,
            "backdateAdmission", AD_BASE,
            "grant", AD_BASE,
            "newTransition", AD_BASE,
            "assertNotFound", AD_BASE,
            "insertEligible", AD_BASE,
            "insertConsent", AD_BASE,
            "claim", AD_BASE,
            "providerBackOn", AD_BASE,
            "concurrently", OPERATOR_BASE);

    static List<String> names() {
        return HOLDERS.keySet().stream().sorted().toList();
    }

    /**
     * 그 이름의 메서드 정의 — 줄 머리의 (애노테이션·수식어·타입 매개변수) 반환 타입 뒤에 이름과 {@code (}가 오는 꼴. 호출은 이름 앞이
     * {@code =}·{@code .}·{@code (}·{@code ->}이거나 {@code return}·{@code new}·{@code throw}·{@code assert} 같은 문 머리 뒤라서
     * 걸리지 않는다.
     */
    static Pattern definitionOf(String name) {
        return Pattern.compile("(?m)^\\s*(?:@[\\w.]+(?:\\([^)]*\\))?\\s+)*"
                + "(?:(?:private|protected|public|static|final|abstract|synchronized)\\s+)*(?:@[\\w.]+\\s+)*"
                + "(?:<[^;(){}=]*>\\s+)?"
                + "(?!(?:return|new|throw|else|yield|assert|case)\\b)[\\w.]+(?:<[^;(){}=]*>)?(?:\\[\\])*\\s+"
                + Pattern.quote(name) + "\\s*\\(");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("names")
    void 광고_시험_helper의_정의는_정해진_base_하나에만_있다(String name) throws IOException {
        String holder = HOLDERS.get(name);
        Pattern family = holder.equals(OPERATOR_BASE) ? OPERATOR_FAMILY : AD_FAMILY;
        assertThat(holders(definitionOf(name), family, holder)).as(name).containsExactly(holder);
    }

    @Test
    void 정의만_걸리고_호출은_걸리지_않는다() {
        Pattern claim = definitionOf("claim");

        assertThat(claim.matcher("    private HttpResponse<String> claim(Signed signed, String t) throws Exception {")
                .find()).isTrue();
        assertThat(claim.matcher("    HttpResponse<String> claim(Signed signed, String t) {").find()).isTrue();
        assertThat(definitionOf("newTransition").matcher("    private static String newTransition() {").find())
                .isTrue();
        assertThat(definitionOf("concurrently").matcher(
                "    protected static List<HttpResponse<String>> concurrently(Call first, Call second) {").find())
                .isTrue();
        assertThat(definitionOf("concurrently").matcher(
                "    private static <T> List<T> concurrently(Callable<T> first, Callable<T> second) {").find())
                .isTrue();
        assertThat(definitionOf("providerBackOn").matcher("    void providerBackOn() {").find()).isTrue();

        assertThat(claim.matcher("        return claim(signed, transition);").find()).isFalse();
        assertThat(claim.matcher("        var shown = claim(signed, transition);").find()).isFalse();
        assertThat(claim.matcher("        assertShow(claim(signed, open));").find()).isFalse();
        assertThat(claim.matcher("        var both = concurrently(() -> claim(a, t), () -> claim(a, t));").find())
                .isFalse();
        assertThat(claim.matcher("        assertThat(this.transitions.claim(owner.user(), t)).isNull();").find())
                .isFalse();
        assertThat(claim.matcher("        assert claim(signed, transition) != null;").find()).isFalse();
    }

    @Test
    void 훑는_범위는_그_base의_하위_클래스와_base_자신이다() {
        assertThat(AD_FAMILY.matcher("class AdEligibilityTest extends AdTestBase {").find()).isTrue();
        assertThat(AD_FAMILY.matcher("class SupportInquiryTest extends OperatorTestBase {").find()).isFalse();
        assertThat(OPERATOR_FAMILY.matcher("class SupportInquiryTest extends OperatorTestBase {").find()).isTrue();
        assertThat(OPERATOR_FAMILY.matcher("class AdTransitionTest extends AdTestBase {").find()).isTrue();
        assertThat(OPERATOR_FAMILY.matcher("class FirstSnapshotTest extends AuthFlowTestBase {").find()).isFalse();
    }

    /** 그 base 자신과 그 base 계열 시험 가운데 그 이름의 정의가 있는 파일({@code src/test/java} 기준 경로). */
    private static List<String> holders(Pattern definition, Pattern family, String holder) throws IOException {
        Path root = Path.of("src/test/java");
        assertThat(root)
                .withFailMessage("시험 소스 %s를 찾지 못했다 — 이 시험은 backend 디렉터리를 working dir로 돈다(Gradle 기본).",
                        root.toAbsolutePath())
                .isDirectory();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .map(path -> Map.entry(root.relativize(path).toString().replace('\\', '/'), read(path)))
                    .filter(file -> file.getKey().equals(holder) || family.matcher(file.getValue()).find())
                    .filter(file -> definition.matcher(file.getValue()).find())
                    .map(Map.Entry::getKey)
                    .sorted()
                    .toList();
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(path.toString(), failure);
        }
    }
}
