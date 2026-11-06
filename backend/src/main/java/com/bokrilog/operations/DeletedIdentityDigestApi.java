package com.bokrilog.operations;

import java.util.Optional;

/**
 * 삭제한 소셜 identity의 keyed digest(Story 5.4 재가입 차단). 키는 감사 digest 키({@code BOKRILOG_AUDIT_DIGEST_KEY})이고 다른 digest와
 * 겹치지 않는 고유 접두사({@code deleted-identity:})를 붙인 HMAC-SHA256이다 — tombstone 키가 아니다(API는 tombstone 키를 받지 않는다).
 *
 * <p>identity가 소유한 port({@code DeletedIdentityDigests})를 루트가 이 API로 구현한다 — identity는 키도 HMAC도 갖지 않는다. 결과는 소문자
 * 16진 64자이고, 원 issuer·subject는 이 값에서 되짚을 수 없다(키 없이 다른 표의 값과 대조할 수도 없다).
 */
public interface DeletedIdentityDigestApi {

    /**
     * 그 identity의 digest.
     *
     * @return 이 프로세스에 감사 digest 키가 없으면(또는 읽을 수 없으면) 빈 값
     */
    Optional<String> digestOf(String issuer, String subject);
}
