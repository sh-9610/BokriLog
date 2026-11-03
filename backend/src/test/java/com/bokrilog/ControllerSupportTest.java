package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.sync.IdempotencyKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

/**
 * 루트 컨트롤러 공통 판독·응답 빌더({@link ControllerSupport})와 멱등 충돌 code({@link IdempotencyKey#CONFLICT_CODE})가 한
 * 벌인지 본다. 앞의 시험들은 helper의 판정·헤더를, 뒤의 둘은 운영 소스를 직접 훑어 사본·리터럴이 다시 생기지 않게 막는다
 * (Gradle 시험의 working dir은 {@code backend}).
 */
class ControllerSupportTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String name) {
        SecurityContextHolder.getContext().setAuthentication(
                new PreAuthenticatedAuthenticationToken(name, null, AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    @Test
    void 인증_주체의_UUID를_읽는다() {
        UUID user = UUID.randomUUID();
        signIn(user.toString());

        assertThat(ControllerSupport.currentUserId()).isEqualTo(user);
    }

    @Test
    void 인증이_없으면_null이다() {
        assertThat(ControllerSupport.currentUserId()).isNull();
    }

    @Test
    void 익명_인증은_null이다() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThat(ControllerSupport.currentUserId()).isNull();
    }

    @Test
    void 인증되지_않은_토큰은_null이다() {
        SecurityContextHolder.getContext().setAuthentication(
                new PreAuthenticatedAuthenticationToken(UUID.randomUUID().toString(), null));

        assertThat(ControllerSupport.currentUserId()).isNull();
    }

    @Test
    void 이름이_UUID가_아니면_예외_없이_null이다() {
        signIn("not-a-uuid");

        assertThat(ControllerSupport.currentUserId()).isNull();
    }

    @Test
    void SENSITIVE_허용이면_사용자를_돌려준다() {
        UUID user = UUID.randomUUID();
        signIn(user.toString());
        AccountAccessApi accounts = mock(AccountAccessApi.class);
        when(accounts.decide(user, user, AccountAccessKind.SENSITIVE)).thenReturn(AccessDecision.ALLOWED);

        assertThat(ControllerSupport.allowedUser(accounts)).isEqualTo(user);
    }

    @Test
    void SENSITIVE_거부면_null이다() {
        UUID user = UUID.randomUUID();
        signIn(user.toString());
        AccountAccessApi accounts = mock(AccountAccessApi.class);
        when(accounts.decide(user, user, AccountAccessKind.SENSITIVE)).thenReturn(AccessDecision.DENIED);

        assertThat(ControllerSupport.allowedUser(accounts)).isNull();
    }

    @Test
    void 미인증이면_접근_판정_없이_null이다() {
        AccountAccessApi accounts = mock(AccountAccessApi.class);

        assertThat(ControllerSupport.allowedUser(accounts)).isNull();
        verify(accounts, never()).decide(any(), any(), any());
    }

    @Test
    void 오류_응답은_JSON_no_store_세_키다() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // 진입점 $request_id 모양(소문자 hex 32자)을 실행 중에 만든다 — 토큰 모양 리터럴을 소스에 두지 않는다.
        String ingress = UUID.randomUUID().toString().replace("-", "");
        request.addHeader(ApiErrorHandler.REQUEST_ID_HEADER, ingress);

        ResponseEntity<ApiErrorHandler.ApiError> response =
                ControllerSupport.error(HttpStatus.CONFLICT, IdempotencyKey.CONFLICT_CODE, "문장", request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getBody()).isEqualTo(new ApiErrorHandler.ApiError("IDEMPOTENCY_CONFLICT", "문장", ingress));
    }

    @Test
    void 요청_식별자가_없으면_correlation_id를_새로_만든다() {
        String correlationId = ControllerSupport.correlationIdOf(new MockHttpServletRequest());

        assertThat(UUID.fromString(correlationId)).isNotNull();
    }

    @Test
    void 전용_record도_같은_헤더_규칙을_빌린다() {
        record Custom(String code) {}

        ResponseEntity<Custom> response = ControllerSupport.noStoreJson(HttpStatus.UNPROCESSABLE_CONTENT,
                new Custom("X"));

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getBody()).isEqualTo(new Custom("X"));
    }

    @Test
    void 운영_소스에서_멱등_충돌_리터럴은_IdempotencyKey에만_있다() throws IOException {
        assertThat(IdempotencyKey.CONFLICT_CODE).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(holders(Pattern.compile(Pattern.quote("\"IDEMPOTENCY_CONFLICT\""))))
                .containsExactly("IdempotencyKey.java");
    }

    @Test
    void 운영_소스에서_세션_사용자_판독과_SENSITIVE_판정은_ControllerSupport에만_있다() throws IOException {
        // 정의(반환 타입 UUID 뒤의 이름)와 판독 본문을 함께 본다 — 이름을 바꾼 사본도 본문에서 걸린다. 본문은 한 문장(다음
        // `;` 전) 안에서 `UUID.fromString(` 뒤에 `getName()`이 오는 모든 꼴이다 — 변수를 거친 판독도, 연쇄 호출
        // `UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName())`도 걸린다.
        assertThat(holders(Pattern.compile("UUID\\s+(currentUserId|allowedUser)\\s*\\(")))
                .containsExactly("ControllerSupport.java");
        assertThat(holders(Pattern.compile("UUID\\.fromString\\([^;]*getName\\(\\)")))
                .containsExactly("ControllerSupport.java");
    }

    @Test
    void 연쇄_호출_판독도_사본으로_걸린다() {
        Pattern body = Pattern.compile("UUID\\.fromString\\([^;]*getName\\(\\)");

        assertThat(body.matcher("return UUID.fromString(SecurityContextHolder.getContext().getAuthentication()\n"
                + "        .getName());").find()).isTrue();
        assertThat(body.matcher("UUID id = UUID.fromString(text); String n = a.getName();").find()).isFalse();
    }

    @Test
    void 운영_소스에서_요청_식별자_헤더_판독은_ControllerSupport와_ApiErrorHandler에만_있다() throws IOException {
        // 다른 클래스가 쓰는 꼴(한정 이름)은 ControllerSupport 하나다.
        assertThat(holders(Pattern.compile(Pattern.quote("getHeader(ApiErrorHandler.REQUEST_ID_HEADER)"))))
                .containsExactly("ControllerSupport.java");
        // 정적 import로 한정자를 뺀 꼴까지 넓히면 상수를 소유한 ApiErrorHandler가 함께 걸린다 — 자기 오류 디스패치
        // (WebRequest 처리기·오류 페이지 컨트롤러)가 같은 규칙을 직접 지나고, 그 응답은 no-store가 없어 여기를 쓰지 않는다.
        assertThat(holders(Pattern.compile("getHeader\\(\\s*(?:ApiErrorHandler\\.)?REQUEST_ID_HEADER\\s*\\)")))
                .containsExactly("ApiErrorHandler.java", "ControllerSupport.java");
    }

    private static List<String> holders(Pattern pattern) throws IOException {
        Path root = Path.of("src/main/java");
        assertThat(root).isDirectory();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> pattern.matcher(read(path)).find())
                    .map(path -> path.getFileName().toString())
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
