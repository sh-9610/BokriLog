package com.bokrilog.operations.internal;

import com.bokrilog.operations.DeletedIdentityDigestApi;
import com.bokrilog.operations.OperatorProperties;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * {@link DeletedIdentityDigestApi} 구현(Story 5.4). 키는 감사 digest 키 그대로이고 접두사가 다르다({@link ActorDigest#ofDeletedIdentity}).
 *
 * <p><b>모든 역할에서 배선한다.</b> 감사 행을 쓰는 역할의 {@link ActorDigest} bean과 달리 키가 없어도 기동을 막지 않는다 — 키 없는 역할(migrate
 * 등)은 로그인·연결·삭제를 하지 않고, 그 역할들이 identity 서비스를 만들 때 이 값이 필요 없다. 키가 있는데 읽을 수 없으면 빈 값으로 접는다
 * (그 키를 실제로 쓰는 역할은 {@link ActorDigest} bean 생성에서 이미 기동을 거부한다). 빈 값을 받은 identity는 삭제를 실패로 닫는다.
 */
@Service
class DeletedIdentityDigestService implements DeletedIdentityDigestApi {

    private final @Nullable ActorDigest digest;

    DeletedIdentityDigestService(OperatorProperties properties) {
        this.digest = digestOf(properties.auditDigestKey());
    }

    private static @Nullable ActorDigest digestOf(@Nullable String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            return new ActorDigest(key);
        } catch (IllegalStateException unreadable) {
            return null;
        }
    }

    @Override
    public Optional<String> digestOf(String issuer, String subject) {
        ActorDigest keyed = this.digest;
        return keyed == null ? Optional.empty() : Optional.of(keyed.ofDeletedIdentity(issuer, subject));
    }
}
