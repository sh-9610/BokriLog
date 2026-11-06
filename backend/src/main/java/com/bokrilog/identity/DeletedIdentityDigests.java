package com.bokrilog.identity;

import java.util.Optional;

/**
 * 삭제한 소셜 identity의 keyed digest(Story 5.4). <b>identity가 소유하고 루트가 구현한다</b> — identity는 키도 HMAC도 갖지 않는다.
 *
 * <p>계정 삭제가 소셜 로그인 연결을 지우기 전에 그 {@code (issuer, subject)}의 digest를 재가입 차단 기록으로 남기고(원 식별자는 남기지
 * 않는다), 로그인·연결이 같은 digest로 그 기록을 본다. 키는 API가 이미 가진 감사 digest 키({@code BOKRILOG_AUDIT_DIGEST_KEY})의 HMAC이고
 * 입력이 고유 접두사로 시작한다 — tombstone 키가 아니다(API는 tombstone 키를 받지 않는다).
 *
 * <p>값이 비면 이 프로세스가 digest를 만들 수 없다(키가 없거나 읽을 수 없다). 감사 digest 키가 비거나 읽을 수 없으면 servlet 프로세스(API·
 * Worker)와 삭제 표본·복원 적용 역할은 기동을 거부하므로(감사 원장 계약) 정상 배치에서는 오지 않는다. 그래도 오면 identity는 <b>fail-closed</b>로
 * 닫는다 — 새 매핑(가입·연결)을 만들지 않고, 지울 매핑이 있는 삭제는 실패로 끝낸다(차단 기록 없는 삭제를 하지 않는다).
 */
public interface DeletedIdentityDigests {

    /**
     * 그 identity의 digest(소문자 16진 64자).
     *
     * @return 키가 없는 프로세스면 빈 값
     */
    Optional<String> digestOf(String issuer, String subject);
}
