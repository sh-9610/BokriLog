package com.bokrilog;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증이 필요 없는 상태 확인 엔드포인트.
 *
 * <p>응답은 {@code {"status":"UP"}} 한 가지뿐이다. 시각·버전·호스트명·빌드 정보·의존 상태를 담지
 * 않는다. 이 엔드포인트는 인증 없이 공개되므로 담는 모든 값이 곧 공개 정보다. 배포 식별과 의존
 * 상태 보고는 인증·권한 경계를 가진 별도 관측 경로가 소유하며, 그 경로는 Story 3.7의 범위다.
 *
 * <p>Actuator를 쓰지 않는 이유도 같다. {@code /actuator/health}는 기본값만으로도 디스크·의존
 * 컴포넌트 상태를 노출할 수 있고, 노출 범위 관리가 곧 새 보안 표면이 된다. 이 스토리에 필요한
 * 것은 "동일 출처에서 서버가 응답한다"는 사실 하나뿐이다.
 */
@RestController
class StatusController {

    /** 고정된 최소 필드. 필드를 늘릴 때는 SPEC이 공개 여부를 먼저 판단한다. */
    record StatusResponse(String status) {}

    @GetMapping(path = "/api/status", produces = MediaType.APPLICATION_JSON_VALUE)
    StatusResponse status() {
        return new StatusResponse("UP");
    }
}
