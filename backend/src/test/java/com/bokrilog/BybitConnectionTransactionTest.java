package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import com.bokrilog.DevBybitStub.Scenario;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.source.SourceRejectionReason;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 외부 호출이 <b>진행되는 동안</b> 관측해야 하는 것들(Story 2.1).
 *
 * <p>이 계약은 구조만으로는 관측되지 않는다. 허가 포트가 선행 트랜잭션을 거절하지만
 * ({@code ActionGrantService.requireOwnedTransactionBoundary}) 그 guard가 닫는 것은 "소비를 부르는
 * 순간 트랜잭션이 열려 있는 경우"뿐이다. 누군가 나중에 검증 호출만 트랜잭션으로 감싸고 그
 * 트랜잭션을 소비 전에 닫으면 guard는 울지 않고 이 AC는 조용히 깨진다. 그래서 <b>검증이 도는
 * 그 순간</b>을 직접 본다.
 *
 * <p>보는 자리는 어댑터 경계이고 보는 주체는 <b>호출 스레드</b>다. 스텁 HTTP 서버의 핸들러
 * 스레드에서 보면 다른 스레드의 사실을 읽는 것이라 아무것도 고정하지 않는다 —
 * {@code TransactionSynchronizationManager}가 thread-local이기 때문이다. 공개 API를
 * {@link MockitoSpyBean}으로 감싸 요청 스레드 위에서 판정한 뒤 실제 구현에 위임한다.
 *
 * <p>축이 둘이다. 하나는 호출 스레드의 활성 트랜잭션이고, 다른 하나는 <b>데이터베이스가 보는</b>
 * 계정 잠금이다 — {@code identity}의 쓰기 경로는 생명주기 행을 {@code SELECT ... FOR UPDATE}로
 * 잠그므로 그 잠금이 열려 있으면 관계 수준 {@code RowShareLock}으로 {@code pg_locks}에 나타난다
 * ({@code identity/AccountConcurrencyTest}의 같은 조회 관용). 앞의 축만 보면 "우리 스레드는
 * 깨끗한데 다른 참여자가 그 계정을 붙들고 있는" 상태가 통과한다.
 *
 * <p>같은 seam이 축 둘을 더 연다. 하나는 <b>왕복 도중의 상태 변화</b>이고 다른 하나는 <b>구성이
 * 비었을 때 왕복이 아예 없다</b>는 사실이다 — 뒤의 것은 컨텍스트 수준의 구성을 바꾸지 않고도
 * {@code isConnectable()} 하나를 거짓으로 만들어 관측할 수 있다. 빈 구성이 실제로 그 값을 거짓으로
 * 만든다는 것은 모듈 단위 시험이 본다({@code source.internal.CredentialCipherTest}·
 * {@code source.internal.BybitAdapterTest}).
 *
 * <p>왕복 도중의 상태 변화: 제공자 호출은 최대 15초이고 그 사이에
 * 운영자가 제한을 켤 수 있다 — 진입 게이트의 판정만 믿으면 그 커밋이 그대로 통과하며 "최종 저장
 * 트랜잭션에서 다시 검사한다"가 다섯 중 하나에 대해 성립하지 않는다.
 *
 * <p>{@link MockitoSpyBean} 때문에 이 클래스만 컨텍스트가 갈라진다({@code AuthDeletionPathTest}와
 * 같은 대가). 컨테이너는 여전히 JVM에 하나다. 시험 몇 개를 위해 치르는 값이며, 그 대신 이 축들이
 * 문장이 아니라 검사로 남는다.
 */
class BybitConnectionTransactionTest extends AuthFlowTestBase {

    private static final String GOOGLE = AuthSecurityConfig.GOOGLE_REGISTRATION_ID;

    /**
     * 검증과 저장의 공개 API.
     *
     * <p>어댑터가 아니라 이 인터페이스를 감싼다. 어댑터는 {@code source.internal}의 package-private
     * 타입이라 루트 패키지의 시험이 참조하면 모듈 경계를 넘고, 여기서 보려는 것은 "외부 호출이
     * 시작되는 자리"이지 그 구현의 내부가 아니다.
     */
    @MockitoSpyBean private SourceConnectionApi sources;

    @Test
    void 외부_호출이_도는_동안_열린_트랜잭션도_계정_잠금도_없다() throws Exception {
        String subject = "no-tx-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        String reauthenticated =
                sessionCookieOf(
                                reauthenticate(
                                        GOOGLE, cookie, csrfTokenOf(cookie), plan -> plan.subject(subject)))
                        .orElseThrow();
        String csrfToken = csrfTokenOf(reauthenticated);

        /*
         * 관측이 실제로 일어났는지부터 붙잡는다. 이 상자가 거짓으로 남으면 아래 두 단정은 초기값을
         * 보는 것이고, 그때 이 시험은 아무것도 검증하지 않은 채 통과한다.
         */
        AtomicBoolean observed = new AtomicBoolean(false);
        // 초기값이 "위반"이다. 관측이 없으면 그 자체로 실패해야 한다.
        AtomicBoolean transactionActive = new AtomicBoolean(true);
        AtomicLong accountLocks = new AtomicLong(-1);

        doAnswer(
                        invocation -> {
                            observed.set(true);
                            // 호출 스레드의 사실이다. 이 값이 참이면 제공자 왕복이 트랜잭션 안에
                            // 있다는 뜻이고, 그 트랜잭션은 왕복이 끝날 때까지 열려 있다.
                            transactionActive.set(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            accountLocks.set(lifecycleRowLocks());
                            return invocation.callRealMethod();
                        })
                .when(this.sources)
                .verify(any(), any());

        HttpResponse<String> connected =
                connectBybitRoundTrip(
                        reauthenticated,
                        csrfToken,
                        BybitStubConfiguration.apiKey(Scenario.UTA2, "700001"),
                        BybitStubConfiguration.newSecret());

        assertThat(connected.statusCode())
                .withFailMessage("연결이 200이 아니다: %s %s", connected.statusCode(), connected.body())
                .isEqualTo(200);
        assertThat(observed)
                .withFailMessage("검증이 불리지 않았다. 아래 두 단정이 아무것도 보지 않는다.")
                .isTrue();
        assertThat(transactionActive)
                .withFailMessage("제공자 호출이 열린 트랜잭션 안에서 돌았다.")
                .isFalse();
        assertThat(accountLocks)
                .withFailMessage("제공자 호출이 도는 동안 계정 생명주기 행이 잠겨 있었다.")
                .hasValue(0L);

        // 그럼에도 저장은 실제로 일어났다. 위 둘이 "아무 일도 하지 않아서" 참인 것이 아니다.
        assertThat(sourceConnectionCount()).isEqualTo(1);
        assertThat(sourceCredentialCount()).isEqualTo(1);
        assertThat(membershipCount()).isEqualTo(1);
    }

    @Test
    void 저장할_수_없는_구성이면_준비가_외부_호출_없이_닫힌다() throws Exception {
        /*
         * fail-closed의 자리가 제공자 **앞**이라는 것이 이 시험의 전부다. 저장 시점에만 판정하면
         * key ring이 없는 스택에서 사용자의 진짜 자격정보가 서명된 요청 세 번으로 거래소에 도착한
         * 뒤에야 거절된다 — 비밀이 우리가 저장할 수도 없는 상태에서 밖으로 나간 셈이다.
         */
        Ready user = ready("unavailable-prepare");
        doReturn(false).when(this.sources).isConnectable();
        BybitStubConfiguration.resetRequestCount();

        HttpResponse<String> prepared = prepareBybitConnection(user.cookie(), user.csrfToken());

        /*
         * 단일 거부(403)가 아니라 구성 부재(422)다. 사용자가 무엇을 해도 통과하지 못하는 상태를
         * "권한이 없습니다"로 접으면 본인 확인만 반복하게 된다.
         */
        assertThat(prepared.statusCode()).isEqualTo(422);
        assertThat(prepared.body())
                .contains(
                        "\"code\":\""
                                + BybitConnectionController.codeOf(SourceRejectionReason.UNAVAILABLE)
                                + "\"");
        assertThat(prepared.body()).contains("\"correlationId\"");
        // 어느 값이 비었는지 알려 주지 않는다.
        assertThat(prepared.body()).doesNotContain("BOKRILOG_");

        assertThat(BybitStubConfiguration.requestCount()).isZero();
        assertThat(actionGrantCount()).isZero();
        assertThat(sourceConnectionCount()).isZero();
    }

    @Test
    void 저장할_수_없는_구성이면_최종_확인도_외부_호출_없이_닫힌다() throws Exception {
        /*
         * 준비가 이미 막으므로 정상 경로로는 여기 닿을 수 없다. 그래도 확인 쪽에 같은 게이트가
         * 있어야 하는 이유는 무장이 살아 있는 채로 구성이 사라지는 경로 때문이다 — 준비 뒤에 키를
         * 회수한 스택이 그 모양이고, 그때 게이트가 하나뿐이면 왕복이 그대로 나간다.
         */
        Ready user = ready("unavailable-connect");
        HttpResponse<String> prepared = prepareBybitConnection(user.cookie(), user.csrfToken());
        assertThat(prepared.statusCode()).isEqualTo(200);
        String confirmation = confirmationIn(prepared.body());
        String reference = grantReferenceOf(user.userId());

        doReturn(false).when(this.sources).isConnectable();
        BybitStubConfiguration.resetRequestCount();

        HttpResponse<String> response =
                connectBybit(
                        user.cookie(),
                        user.csrfToken(),
                        BybitStubConfiguration.apiKey(Scenario.UTA2, "700003"),
                        BybitStubConfiguration.newSecret(),
                        confirmation);

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body())
                .contains(
                        "\"code\":\""
                                + BybitConnectionController.codeOf(SourceRejectionReason.UNAVAILABLE)
                                + "\"");
        assertThat(BybitStubConfiguration.requestCount()).isZero();
        assertThat(sourceConnectionCount()).isZero();
        // 허가는 소비되지 않았다. 구성이 돌아오면 같은 무장으로 이어 갈 수 있다.
        assertThat(actionGrantConsumed(reference)).isFalse();
    }

    @Test
    void 왕복_도중에_켜진_운영_제한이_최종_커밋을_막는다() throws Exception {
        String subject = "restricted-midflight-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        String reauthenticated =
                sessionCookieOf(
                                reauthenticate(
                                        GOOGLE, cookie, csrfTokenOf(cookie), plan -> plan.subject(subject)))
                        .orElseThrow();
        String csrfToken = csrfTokenOf(reauthenticated);

        HttpResponse<String> prepared = prepareBybitConnection(reauthenticated, csrfToken);
        assertThat(prepared.statusCode()).isEqualTo(200);
        String confirmation = confirmationIn(prepared.body());
        String reference = grantReferenceOf(userId);

        /*
         * 제공자 왕복이 도는 **그 순간** 제한을 켠다. 진입 게이트는 이미 지났으므로, 최종 저장
         * 트랜잭션이 다시 보지 않으면 이 커밋은 그대로 통과한다.
         */
        AtomicBoolean observed = new AtomicBoolean(false);
        doAnswer(
                        invocation -> {
                            observed.set(true);
                            restrictCommand(BybitConnectionService.COMMAND);
                            return invocation.callRealMethod();
                        })
                .when(this.sources)
                .verify(any(), any());

        HttpResponse<String> response =
                connectBybit(
                        reauthenticated,
                        csrfToken,
                        BybitStubConfiguration.apiKey(Scenario.UTA2, "700002"),
                        BybitStubConfiguration.newSecret(),
                        confirmation);

        assertThat(observed)
                .withFailMessage("검증이 불리지 않아 제한이 켜지지 않았다.")
                .isTrue();
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":\"FORBIDDEN\"");
        // 저장이 하나도 없다. 사설 예외가 소비 UPDATE까지 함께 되돌렸다.
        assertThat(sourceConnectionCount()).isZero();
        assertThat(sourceCredentialCount()).isZero();
        assertThat(providerAccountCount()).isZero();
        assertThat(portfolioCount()).isZero();
        assertThat(membershipCount()).isZero();
        assertThat(actionGrantConsumed(reference)).isFalse();
    }

    /** 로그인과 재인증을 마친 사용자 하나({@code BybitConnectionTest}의 같은 레시피). */
    private record Ready(UUID userId, String cookie, String csrfToken) {}

    private Ready ready(String prefix) throws Exception {
        String subject = prefix + "-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        String rotated =
                sessionCookieOf(
                                reauthenticate(
                                        GOOGLE, cookie, csrfTokenOf(cookie), plan -> plan.subject(subject)))
                        .orElseThrow();
        return new Ready(userId, rotated, csrfTokenOf(rotated));
    }

    /**
     * 그 사용자의 허가 참조.
     *
     * <p>브라우저로 나가지 않는 값이라 응답에서 읽을 수 없다. 사용자당 살아 있는 허가는 하나라
     * 이 조회로 행이 정해진다({@code BybitConnectionTest}의 같은 헬퍼).
     */
    private String grantReferenceOf(UUID userId) {
        return jdbc()
                .sql("SELECT grant_id FROM action_grant WHERE user_id = :userId")
                .param("userId", userId)
                .query(String.class)
                .single();
    }

    /**
     * 지금 {@code identity_account_lifecycle}에 걸린 쓰기 성격 잠금 수.
     *
     * <p>{@code AccessShareLock}을 뺀다. 그것은 평범한 {@code SELECT}가 잡는 잠금이고 이 조회
     * 자신도 시스템 카탈로그에 같은 것을 잡는다 — 세면 언제나 0이 아니다. {@code SELECT ... FOR
     * UPDATE}가 잡는 {@code RowShareLock}부터가 "이 계정을 붙들고 있다"의 신호이며, 그 위의 모든
     * 모드가 함께 걸린다.
     *
     * <p>{@code pid}로 좁히지 않는다. 확인하려는 것은 "우리 스레드가 잠그지 않았다"가 아니라
     * "이 순간 그 행을 붙들고 있는 참여자가 없다"이고, 뒤의 것이 AC가 말하는 DB 상태다.
     */
    private long lifecycleRowLocks() {
        return jdbc()
                .sql(
                        "SELECT count(*) FROM pg_locks lock_row "
                                + "JOIN pg_class relation ON relation.oid = lock_row.relation "
                                + "WHERE relation.relname = 'identity_account_lifecycle' "
                                + "AND lock_row.mode <> 'AccessShareLock'")
                .query(Long.class)
                .single();
    }
}
