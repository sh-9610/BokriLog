- source_spec: `/Users/hwan/Desktop/SeongHwan/Side_Project/BokriLog/_bmad-output/implementation-artifacts/spec-bmad-environment-setup.md`
  summary: 장기 미접속 시 Bybit Transaction Log 보존기간 밖 자금흐름을 탐지·격리하는 규칙을 계산 SPEC에서 결정한다.
  evidence: 화면 기반 동기화와 API 보존기간 제한이 함께 적용되면 장기 공백 동안의 자금흐름을 놓칠 수 있다.
- source_spec: `/Users/hwan/Desktop/SeongHwan/Side_Project/BokriLog/_bmad-output/implementation-artifacts/spec-bmad-environment-setup.md`
  summary: Product Brief 부록에서 Forge 정본을 정확한 상대경로 링크로 연결한다.
  evidence: 현재 `forged-idea.md` 표기는 부록 위치를 기준으로 실제 정본 파일을 찾을 수 없는 단순 코드 표기다.
- source_spec: `/Users/hwan/Desktop/SeongHwan/Side_Project/BokriLog/_bmad-output/implementation-artifacts/spec-bmad-environment-setup.md`
  summary: Day 60 고정 IP 전환 실패 시 신규 연결 중단과 Day 90 종료 조건을 PRD 운영 규칙으로 명시한다.
  evidence: 브리프는 전환 가능 여부 판단과 Day 90 이후 요청 중단만 적어 중간 운영 분기가 생략되어 있다.
- source_spec: `/Users/hwan/Desktop/SeongHwan/Side_Project/BokriLog/_bmad-output/implementation-artifacts/spec-bmad-environment-setup.md`
  summary: 사용자 요청에 따른 계정·계획·스냅샷·이벤트·백업 삭제 범위와 완료 시한을 개인정보 설계에서 결정한다.
  evidence: 현재 브리프는 연결 해제 시 자격정보 삭제만 정하고 다른 사용자 데이터의 삭제 경로는 정하지 않았다.
- source_spec: `/Users/hwan/Desktop/SeongHwan/Side_Project/BokriLog/_bmad-output/implementation-artifacts/spec-bmad-environment-setup.md`
  summary: 공개 베타 활성화 완료율의 분모와 측정 기간을 PRD 지표 정의에 추가한다.
  evidence: 테스트 사용자, 연결 시도자, 유효 키 제출자 중 어느 집단을 80% 기준의 분모로 삼는지 현재 브리프만으로 재현할 수 없다.
- source_spec: `/Users/hwan/Desktop/SeongHwan/Side_Project/BokriLog/_bmad-output/implementation-artifacts/spec-bmad-environment-setup.md`
  summary: 동기화 성공률의 측정 단위와 재시도·API 제한·무효 키 제외 규칙을 PRD에서 정의한다.
  evidence: 현재 95% 기준은 어떤 요청과 실패를 분모에 포함하는지 명시하지 않는다.
- source_spec: `/Users/hwan/Desktop/SeongHwan/Side_Project/BokriLog/_bmad-output/implementation-artifacts/spec-bmad-environment-setup.md`
  summary: 이해도 80%를 판정할 문항·합격 기준·응답 대상·측정 시점을 PRD에서 정의한다.
  evidence: 현재 브리프는 네 개 개념을 올바르게 해석한다는 결과만 있고 반복 가능한 평가 방법이 없다.
- source_spec: `/Users/hwan/Desktop/SeongHwan/Side_Project/BokriLog/_bmad-output/implementation-artifacts/spec-bmad-environment-setup.md`
  summary: 주 2시간 운영 목표의 안정화 시작 조건과 측정 주수를 PRD에서 정의한다.
  evidence: 현재 `안정화 후`라는 표현만으로는 측정 시작점을 일관되게 판정할 수 없다.
- source_spec: `/Users/hwan/Desktop/SeongHwan/Side_Project/BokriLog/_bmad-output/implementation-artifacts/spec-bmad-environment-setup.md`
  summary: 강한 신호별 자동 정지 발동 조건과 60초 측정 시작·완료 시점을 보안 운영 SPEC에서 정의한다.
  evidence: 현재 정책은 자동 정지 목표 시간은 정하지만 신호별 임계치와 측정 경계는 후속 결정으로 남겨 둔다.
- source_spec: `/Users/hwan/Desktop/SeongHwan/Side_Project/BokriLog/_bmad-output/implementation-artifacts/spec-bmad-environment-setup.md`
  summary: 계산 사고에서 `last_trusted_only` 표시를 허용할 계산 버전·입력 무결성 조건을 아키텍처에서 정의한다.
  evidence: 잘못된 계산 버전이 만든 과거 값까지 마지막 신뢰값으로 계속 노출될 가능성을 현재 정책이 명시적으로 차단하지 않는다.
