package com.bokrilog;

import com.bokrilog.operations.OperatorProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.stereotype.Component;

/**
 * 서비스 공지의 지원 연락처(Story 3.5).
 *
 * <p><b>배포 설정 하나가 유일한 출처다</b>({@code bokrilog.operations.support-contact}, env {@code BOKRILOG_SUPPORT_CONTACT}).
 * 비어 있으면 공지 발행이 {@code SUPPORT_CONTACT_UNCONFIGURED}로 거절되고 사용자 공지에 연락처가 실리지 않는다 — 문의
 * 접수(Story 3.6) 전이라 화면이 열 문의 화면이 없고, 설정된 연락처만 진짜 경로다. 허위·빈 지원 링크를 만들지 않는다.
 *
 * <p>servlet 웹 애플리케이션에서만 배선한다(공지 조회·발행이 API 프로세스에만 있다).
 */
@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
class SupportContact {

    /** 지금 연락처. 운영에서는 기동 시 설정 그대로이고 바뀌지 않는다(바꾸는 것은 재배포다). */
    private volatile @Nullable String value;

    SupportContact(OperatorProperties properties) {
        this.value = properties.supportContact();
    }

    /** 설정된 연락처. 없으면 {@code null}이다. */
    @Nullable String value() {
        return this.value;
    }

    /**
     * 연락처를 다시 해석한다 — <b>시험 훅</b>이다({@code OperatorAccess.reinterpret}와 같은 관용). 설정 원문을 같은 파서로
     * 읽으므로 형식 오류는 같은 예외다. 제품 경로에서 부르는 곳이 없다(HTTP 표면도 없다).
     */
    void reinterpret(@Nullable String contact) {
        this.value = OperatorProperties.parseSupportContact(contact);
    }
}
