package com.bokrilog;

import com.bokrilog.identity.DeletedIdentityDigests;
import com.bokrilog.operations.DeletedIdentityDigestApi;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 재가입 차단 digest({@link DeletedIdentityDigests}, Story 5.4)의 루트 구현. identity가 소유한 port를 operations의 공개 API로 잇는다 —
 * identity는 키도 HMAC도 갖지 않고, operations는 identity를 모른다({@link IdentityAccountErasureState}의 반대 방향 관용). 모든 역할에서
 * 배선한다(identity 서비스가 모든 역할에 있다).
 */
@Component
class DeletedIdentityDigestPort implements DeletedIdentityDigests {

    private final DeletedIdentityDigestApi digests;

    DeletedIdentityDigestPort(DeletedIdentityDigestApi digests) {
        this.digests = digests;
    }

    @Override
    public Optional<String> digestOf(String issuer, String subject) {
        return this.digests.digestOf(issuer, subject);
    }
}
