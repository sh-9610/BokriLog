package com.bokrilog;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 재인증 허가의 값 표현과 정책 상수.
 *
 * <p>허가는 <b>다섯</b>에 귀속한다 — user·session·command·target·canonical confirmation hash
 * ({@link Binding}). 다섯 중 하나라도 어긋나면 소비되지 않고, 어느 축이 어긋났는지는 호출자에게
 * 알려 주지 않는다.
 *
 * <p>여기에는 시각 판정이 없다. 만료는 {@code expires_at}을 술어로 보는 소비 UPDATE 한 문장이
 * 판정하고 그 시각의 권위는 PostgreSQL 하나다({@code ActionGrantRepository}). 이 클래스가 드는
 * 수명 값은 데이터베이스에 건네는 정책 숫자일 뿐 자바가 비교하는 값이 아니다.
 *
 * <p>정책 상수를 Story 1.8과 공유하지 않는다. {@code ReauthenticationFlow}의 신선도 창은 OIDC
 * {@code max_age} 프로토콜 값이고 무장 수명은 OAuth 왕복 시간이며, 아래 두 값은 사람의 시간이다 —
 * 확인 화면과 실행 사이, 그리고 본인 확인과 허가 발급 사이. 같은 숫자라는 이유로 하나를 공유하면
 * 프로토콜 값이 바뀌는 날 허가 정책이 조용히 따라 움직인다.
 */
final class ActionGrant {

    /**
     * 허가의 수명.
     *
     * <p>발급 시각부터 <b>5분 미만</b>이다. 발급이 {@code expires_at}을 이 값으로 못 박고
     * {@code now() >= expires_at}이면 만료다. 확인 화면을 본 사람이 실행을 누르기까지의 시간이며,
     * 그 이상 열어 두면 화면을 띄운 채 자리를 뜬 세션이 그대로 실행 권한이 된다.
     */
    static final Duration LIFETIME = Duration.ofMinutes(5);

    /**
     * 발급이 인정하는 재인증 신선도.
     *
     * <p>{@code SECURITY-ACTION-1.0}의 "성공한 재인증 후 5분 이내 발급"이다. 세션에 재인증 시각이
     * 없거나 이 창을 넘기면 발급하지 않는다. 판정은 발급 INSERT의 술어가 하므로 이 값도 자바가
     * 비교하지 않는다.
     *
     * <p>위 {@link #LIFETIME}과 숫자가 같지만 다른 축이다. 하나는 본인 확인에서 허가까지, 다른
     * 하나는 허가에서 실행까지이며 한쪽만 바꾸는 날이 와도 다른 쪽이 따라가면 안 된다.
     */
    static final Duration REAUTHENTICATION_WINDOW = Duration.ofMinutes(5);

    /**
     * 재인증 시각의 미래 쪽 허용 오차.
     *
     * <p>발급 게이트의 과거 쪽 경계는 위 {@link #REAUTHENTICATION_WINDOW}이고 미래 쪽 경계가 이
     * 값이다. <b>둘 다 있어야 창이 닫힌다.</b> 세션에 기록된 재인증 시각만은 데이터베이스가 아니라
     * 애플리케이션이 쓰므로({@code AuthFlowHandlers}), 앞선 시계를 가진 인스턴스가 미래 시각을
     * 기록하면 과거 쪽 술어 하나로는 그 값이 창을 영원히 만족한다.
     *
     * <p>0이 아닌 이유는 그 반대편이다. 인스턴스 사이의 작은 시계 차이로 정상 재인증이 거절되면
     * 안 된다. 60초는 Story 1.8이 제공자 시계에 준 것과 같은 크기지만 다른 상수다 — 그쪽은 남의
     * 시계를 향한 값이고 이쪽은 우리 인스턴스들 사이의 값이다.
     */
    static final Duration REAUTHENTICATION_FUTURE_SKEW = Duration.ofSeconds(60);

    /**
     * 불투명 참조의 난수 길이(바이트).
     *
     * <p>32바이트면 추측이 성립하지 않는다. UUID를 쓰지 않는 이유는 두 가지다 — 122비트뿐이고,
     * 판본에 따라 생성 시각을 값 안에 담는다({@code UUIDv7}). 참조에서 시각을 복원할 수 없어야
     * 한다는 것이 이 값의 계약이다.
     */
    static final int REFERENCE_BYTES = 32;

    /**
     * 불투명 참조의 길이 상한(바이트).
     *
     * <p>{@link #newReference}가 만드는 값은 언제나 43바이트지만 상한을 그보다 넉넉히 둔다. 소비가
     * 받는 참조는 클라이언트가 보낸 값이고, 그 경로에서 걸러야 하는 것은 "우리가 만든 길이인가"가
     * 아니라 "질의에 실어도 되는 크기인가"다. {@code V3__action_grant.sql}의 CHECK와 한 쌍이다.
     */
    static final int MAX_GRANT_REFERENCE_BYTES = 128;

    /** {@code command} 길이 상한(바이트). {@code V3__action_grant.sql}의 CHECK와 한 쌍이다. */
    static final int MAX_COMMAND_BYTES = 64;

    /** {@code target} 길이 상한(바이트). 마찬가지로 스키마 CHECK와 한 쌍이다. */
    static final int MAX_TARGET_BYTES = 256;

    /** {@code session_id} 길이 상한(바이트). */
    static final int MAX_SESSION_ID_BYTES = 128;

    /** {@code operation_id} 길이 상한(바이트). */
    static final int MAX_OPERATION_ID_BYTES = 128;

    /**
     * 확인 문구 원문의 길이 상한(바이트).
     *
     * <p>저장되는 것은 해시뿐이라 스키마에 대응 CHECK가 없다. 그래도 상한을 두는 이유는 해시 계산이
     * 임의 크기 입력을 받는 경로가 되지 않게 하기 위해서다.
     */
    static final int MAX_CONFIRMATION_BYTES = 4096;

    /** 저장되는 결과의 길이 상한(바이트). {@code V3__action_grant.sql}의 CHECK와 한 쌍이다. */
    static final int MAX_RESULT_BYTES = 4096;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Base64.Encoder REFERENCE_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private ActionGrant() {}

    /**
     * 허가가 귀속하는 다섯.
     *
     * <p>발급이 이 값을 행에 적고 소비 UPDATE가 같은 값을 술어로 다시 판정한다. 한 record로 묶는
     * 이유는 두 경로가 같은 다섯을 본다는 것을 시그니처로 강제하기 위해서다 — 축이 하나 빠지면
     * 컴파일되지 않는다.
     *
     * @param userId 인증 세션이 결정한 내부 식별자. 클라이언트가 보낸 값이 아니다
     * @param sessionId 발급 시점 세션의 id. 소비 시점에도 <b>지금 그 요청의 세션</b>에서 읽는다
     * @param command 민감 작업의 이름. 이 포트는 값을 해석하지 않는다
     * @param target 작업 대상. 마찬가지로 해석하지 않는다
     * @param confirmationHash {@link #canonicalConfirmationHash}가 만든 값. 원문은 저장하지 않는다
     */
    record Binding(
            UUID userId, String sessionId, String command, String target, String confirmationHash) {}

    /**
     * 새 불투명 참조.
     *
     * <p>브라우저에 나가는 유일한 값이다. 난수뿐이라 사용자·작업·대상·시각을 복원할 수 없고,
     * 참조 하나를 들고 있다는 사실만으로는 다른 네 귀속을 맞출 수 없어 소비도 성립하지 않는다.
     */
    static String newReference() {
        byte[] bytes = new byte[REFERENCE_BYTES];
        RANDOM.nextBytes(bytes);
        return REFERENCE_ENCODER.encodeToString(bytes);
    }

    /**
     * 확인 문구의 정규화된 해시. 이 값의 유일한 생산자다.
     *
     * <p>소비자마다 다른 정규화를 발명하면 발급과 소비가 서로 다른 문자열을 해시하는 날이 오고,
     * 그때 실패는 "귀속 불일치"라는 구분되지 않는 거부 하나로만 보인다. 그래서 발급도 소비도 이
     * 메서드 하나를 지난다.
     *
     * <p>정규화는 {@link #normalize} 하나다. 대소문자는 접지 않는다 — 확인 문구는 사용자가 화면에
     * 보이는 그대로 옮겨 적는 값이고, 접으면 "delete"와 "DELETE"가 같은 확인이 된다.
     *
     * <p><b>세 값 모두 같은 정규화를 지난다.</b> {@code command}·{@code target}은 컬럼에도 저장되고
     * 소비 술어가 그 컬럼을 비교하므로, 저장 전에도 같은 함수를 지나야 한다
     * ({@code ActionGrantService.bindingOf}). 한쪽만 정규화하면 {@code " link-provider"} 같은 값이
     * 해시는 맞고 컬럼은 어긋나 영영 소비할 수 없는 허가가 된다.
     *
     * <p>세 값을 길이 접두사로 감싸 잇는다. 구분자 없이 이으면 {@code ("ab","c")}와
     * {@code ("a","bc")}가 같은 바이트열이 되어 서로 다른 확인이 같은 해시를 갖는다.
     *
     * @return 소문자 16진 64자
     */
    static String canonicalConfirmationHash(String command, String target, String confirmation) {
        MessageDigest digest = sha256();
        update(digest, command);
        update(digest, target);
        update(digest, confirmation);
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 저장과 해시가 함께 쓰는 정규화. 유니코드 NFC와 양 끝 공백 제거뿐이다.
     *
     * <p>멱등하다. 그래서 정규화된 값을 다시 넣어도 같은 값이고, 저장된 컬럼으로 만든 해시와 원문으로
     * 만든 해시가 같다.
     */
    static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).strip();
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = normalize(value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            // 모든 JDK 구현이 SHA-256을 제공한다. 여기 오면 플랫폼이 계약을 깬 것이고, 조용히
            // 약한 해시로 내려가는 대신 드러낸다. 메시지에 입력을 담지 않는다.
            throw new IllegalStateException("SHA-256을 쓸 수 없다.", impossible);
        }
    }

    /**
     * 받아들일 수 있는 값인지 본다.
     *
     * <p>{@code null}·빈 문자열·공백만인 값과 상한을 넘는 값을 거부한다. 길이를 애플리케이션에서
     * 먼저 값으로 거르는 이유는 위반의 모양 때문이다 — 스키마 CHECK 위반은 거부 값이 아니라
     * 트랜잭션을 abort시키는 예외이고, 그 트랜잭션이 호출자의 것이면 호출자의 작업까지 사라진다
     * ({@code AccountLifecycleService.isAcceptable}과 같은 근거).
     *
     * <p>바이트로 센다. 스키마의 {@code octet_length}와 같은 단위여야 한쪽만 통과하는 값이 없다.
     */
    static boolean isAcceptable(@Nullable String value, int maxBytes) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.getBytes(StandardCharsets.UTF_8).length <= maxBytes;
    }

    /**
     * 저장할 수 있는 결과인지 본다.
     *
     * <p>{@link #isAcceptable}과 달리 빈 값을 허용한다. 결과를 남기지 않는 command가 있고, 그 경우
     * 저장되는 값은 빈 문자열이다. 상한만 {@link #MAX_RESULT_BYTES}로 같은 규칙을 지킨다 —
     * 이 게이트가 없으면 스키마 CHECK 위반이 <b>업무 변경이 이미 실행된 뒤</b> 호출자 트랜잭션을
     * abort시키고, 이 클래스가 선언한 "애플리케이션이 먼저 거르고 CHECK는 최종 방어"가 이 값에서만
     * 뒤집힌다.
     */
    static boolean isStorableResult(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length <= MAX_RESULT_BYTES;
    }
}
