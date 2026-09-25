package com.bokrilog.operations.internal;

import com.bokrilog.operations.OperationalRestrictionApi;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * {@link OperationalRestrictionApi} 구현. {@code internal}의 package-private 클래스다.
 *
 * <p>트랜잭션을 열지 않는다. 문장 하나라 그 자체로 원자적이고, admission은 이 판정을 외부 호출
 * <b>앞에서</b> 한 번 그리고 최종 저장 트랜잭션 안에서 한 번 더 부른다.
 */
@Service
class OperationalRestrictionService implements OperationalRestrictionApi {

    private final OperationalRestrictionRepository repository;

    OperationalRestrictionService(OperationalRestrictionRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean isRestricted(@Nullable String command) {
        if (command == null || command.isBlank()) {
            // 이름을 모르는 요청은 막는다. fail-closed가 기본값이다.
            return true;
        }
        return this.repository.isRestricted(command);
    }
}
