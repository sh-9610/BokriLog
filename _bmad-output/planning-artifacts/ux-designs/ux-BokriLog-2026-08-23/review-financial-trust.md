---
title: BokriLog UX financial trust, privacy, and sustained-use review
status: complete
created: 2026-08-23
reviewer: financial-trust
scope:
  - DESIGN.md
  - EXPERIENCE.md
  - .memlog.md
  - .working/source-extract.md
  - ../../prds/prd-BokriLog-2026-08-23/prd.md
  - ../../prds/prd-BokriLog-2026-08-23/addendum.md
---

# 금융 신뢰·개인정보·지속 이용 검토

## Verdict

**수정 후 통과(conditional pass).** 치명적 결함은 없고, `0`과 오래된 값의 구분, 음수·100% 초과 값 보존, 원자적 스냅샷 교체, 광고 실패 후 결과 보존, 읽기 전용 연결, 비게임화 재방문 원칙은 강하다. 다만 광고 동의·제3자 데이터 경계, 브라우저 캐시의 사용자 데이터 경계, 공식 지표의 재현 근거, 계정 삭제 결과가 구현 가능한 수준으로 닫히지 않았다. 이 네 항목은 공개 베타 전에 해결해야 한다.

| Severity | Count |
|---|---:|
| Critical | 0 |
| High | 4 |
| Medium | 4 |
| Low | 1 |

## Findings

### FT-01 — 광고 동의와 제3자 데이터 경계가 화면·상태 계약으로 닫히지 않았다

**Severity: High**

**Evidence.** `EXPERIENCE.md`는 광고 공급자·동의·데이터 흐름을 미결정으로 남기고([EXPERIENCE.md:25](./EXPERIENCE.md#L25)), 설정에서 “광고 동의”를 관리한다고만 하며([EXPERIENCE.md:42](./EXPERIENCE.md#L42)), 동의 거부를 결과 이동 분기로만 다룬다([EXPERIENCE.md:76](./EXPERIENCE.md#L76), [EXPERIENCE.md:118](./EXPERIENCE.md#L118)). 반면 소스는 제3자 요청 전에 필요한 동의와 비개인화 모드를 적용하고, IP·사용자 에이전트·쿠키/로컬 저장소·URL·페이지 제목·동의 상태·공급자 식별자의 데이터 흐름을 관리하며, URL·제목·슬롯명에 금융값이나 사용자 식별자를 넣지 말라고 명시한다([source-extract.md:242](./.working/source-extract.md#L242)–[250](./.working/source-extract.md#L250), [source-extract.md:324](./.working/source-extract.md#L324)–[329](./.working/source-extract.md#L329)).

**Consequence.** 구현자는 단순 토글만 만들거나 동의 전 공급자 SDK를 초기화할 수 있다. 금융값을 뺀 중립 DOM이어도 URL, 페이지 제목, 슬롯명, 쿠키 또는 공급자 ID를 통해 사용자·금융 문맥이 새어 나가면 사용자의 신뢰와 FR-15/NFR-11을 동시에 훼손한다.

**Required fix.** `EXPERIENCE.md`에 광고 동의 전용 상태 계약을 추가한다. 최소한 (1) 제3자와 공유되는 범주 및 비개인화/문맥형 한정 설명, (2) 거부해도 동기화·결과 조회 가능, (3) 철회가 다음 광고 요청부터 효력을 갖는 방식, (4) 동의 전 광고 SDK/네트워크 요청 금지, (5) 중립 라우트의 DOM·URL·제목·슬롯명에 금융값·신뢰 상태·사용자 식별자 금지, (6) 대시보드 DOM 문맥 수집 공급자 배제, (7) 공급자 미결정 시 광고 없는 출시를 명시한다.

### FT-02 — 캐시된 금융값 표시는 소스에서 확정되지 않은 기능이며 세션·기기 개인정보 경계가 없다

**Severity: High**

**Evidence.** `EXPERIENCE.md`는 네트워크 단절 시 “캐시된 마지막 정상값”을 표시할 수 있다고 명세한다([EXPERIENCE.md:153](./EXPERIENCE.md#L153)). 그러나 소스는 오프라인 모드를 미결정으로 둔다([source-extract.md:344](./.working/source-extract.md#L344)). 같은 문서는 세션 만료 시 민감 표면을 가린다고만 하고([EXPERIENCE.md:69](./EXPERIENCE.md#L69)), 로컬 저장소·브라우저 캐시·서비스 워커·뒤로 가기 캐시에서 금융 상세값을 제거하는 계약은 없다. PRD는 금융 상세값이 화면 외 로그·오류 추적·분석·광고 도구에 기록되지 않아야 하며([source-extract.md:277](./.working/source-extract.md#L277)–[284](./.working/source-extract.md#L284)), 사용자 소유권 경계를 요구한다([source-extract.md:316](./.working/source-extract.md#L316)–[322](./.working/source-extract.md#L322)).

**Consequence.** 공유 기기에서 로그아웃·세션 만료 뒤 이전 사용자의 자산과 계획값이 노출되거나, 계정 삭제 뒤 클라이언트 캐시에 잔존할 수 있다. 또한 확정되지 않은 오프라인 기능을 UX 스파인이 사실상 제품 계약으로 승격한다.

**Required fix.** MVP에서는 해당 문장을 제거하거나 `[ASSUMPTION]`이 아니라 명시적 오픈 질문/출시 차단 결정으로 돌린다. 캐시 표시를 채택한다면 민감 금융 응답의 지속 캐시 금지 또는 암호화·계정 바인딩, 로그아웃/세션 만료/계정 삭제 시 정리, `Cache-Control`/뒤로 가기 복원 처리, 다른 계정 로그인 시 격리, 오프라인 표시에 정확한 측정 시각과 `최신 아님` 강제 조건을 별도 계약으로 정한다.

### FT-03 — 공식 지표의 재현 근거에 스냅샷 ID와 입력 계보가 빠져 있다

**Severity: High**

**Evidence.** 신뢰 상세와 지표 카드는 원인·측정 시각·계산 버전을 제공하지만([EXPERIENCE.md:70](./EXPERIENCE.md#L70)–[71](./EXPERIENCE.md#L71), [EXPERIENCE.md:91](./EXPERIENCE.md#L91)–[95](./EXPERIENCE.md#L95)), 스냅샷 ID, 공통 기준시점 `tc`, 현재가치 측정 시각과 자금흐름 이력 버전의 연결은 명시하지 않는다. `DESIGN.md`도 스냅샷 ID를 글꼴 사용 예로만 언급한다([DESIGN.md:156](./DESIGN.md#L156)); `history-row`에는 사건과 계산 버전은 있으나 결과에서 해당 원 사건 묶음으로 가는 역참조가 없다([DESIGN.md:180](./DESIGN.md#L180)). 소스의 NFR-2는 모든 공식 지표가 스냅샷 ID·계산 버전·기준 시각·외부 자금흐름 이력으로 재현 가능해야 한다([source-extract.md:269](./.working/source-extract.md#L269)–[274](./.working/source-extract.md#L274)).

**Consequence.** 사용자는 숫자가 왜 바뀌었는지 확인할 수 없고, 지원·감사 과정도 화면에 보인 결과를 정확한 입력 묶음과 연결하지 못한다. “공식”이 검증 가능한 상태가 아니라 단순 라벨로 보일 수 있다.

**Required fix.** 각 스냅샷 묶음에 `계산 근거 보기` 진입점을 두고 스냅샷 ID, 공통 기준시점, 현재가치 측정 시각, 계산 버전, 반영된 조정 이력 버전/사건 링크를 한 화면에서 제공한다. 민감 내부 식별자는 복사 가능한 불투명 ID로 제한하고 사용자/계정 식별자는 노출하지 않는다. 이전 계산 버전은 덮지 않고 비교 가능한 읽기 전용 근거로 남긴다.

### FT-04 — 계정 삭제 UX가 실제 삭제 범위와 완료 상태를 보장하지 않는다

**Severity: High**

**Evidence.** 계정 삭제는 대상명 재입력과 “보존 고지”만 명시되어 있고([EXPERIENCE.md:74](./EXPERIENCE.md#L74), [EXPERIENCE.md:121](./EXPERIENCE.md#L121)), 흐름에서도 별도 강한 확인과 법률상 보존 고지만 언급한다([EXPERIENCE.md:250](./EXPERIENCE.md#L250)). PRD는 사용자 데이터와 자격정보를 삭제할 수 있어야 하며 법률상 감사 기록이 남는다면 범위와 기간을 별도로 고지해야 한다([source-extract.md:277](./.working/source-extract.md#L277)–[284](./.working/source-extract.md#L284), [source-extract.md:316](./.working/source-extract.md#L316)–[322](./.working/source-extract.md#L322)).

**Consequence.** 사용자는 계획·스냅샷·조정 이력·계산 버전·연결 자격정보 중 무엇이 즉시 삭제되고 무엇이 얼마나 오래 남는지 판단할 수 없다. 비동기 또는 부분 실패가 발생하면 삭제되었다고 오인할 수 있다.

**Required fix.** 계정 삭제 전용 계약에 삭제 대상 전체 목록, 법률상 보존 대상과 정확한 기간/목적, 즉시 삭제와 지연 삭제의 구분, 진행/완료/실패 상태, 실패 시 삭제되지 않은 범위와 재시도 경로, 완료 뒤 세션 종료와 재접근 불가를 명시한다. 보존 범위·기간이 미확정이면 확정된 문구 없이 계정 삭제 UX를 최종화하지 않는다.

### FT-05 — 공식·추정·계산 불가의 지표별 전파 규칙이 구현 표로 닫히지 않았다

**Severity: Medium**

**Evidence.** 문서는 현재가치와 자금흐름 의존 지표의 계산 성격을 분리하고 추정을 의존 지표 전체에 전파하라고 한다([EXPERIENCE.md:95](./EXPERIENCE.md#L95)). 그러나 대시보드 성공 시 여섯 지표와 세 축을 모두 같은 렌더 커밋으로 교체한다는 규칙([EXPERIENCE.md:99](./EXPERIENCE.md#L99)–[105](./EXPERIENCE.md#L105))과, `검토 필요`에서 “일부 지표가 갱신되지 않음”이라는 여정([EXPERIENCE.md:211](./EXPERIENCE.md#L211)–[218](./EXPERIENCE.md#L218)) 사이에 정확한 표시 조합이 없다. 소스는 추정 흐름에서 현재가치만 `공식`, 나머지 다섯 의존 지표는 모두 `추정`이어야 한다고 명시한다([source-extract.md:215](./.working/source-extract.md#L215)–[222](./.working/source-extract.md#L222)).

**Consequence.** 팀별로 “일부 최신 현재가치 + 이전 파생값”을 같은 묶음처럼 표시하거나, 단일 계산 성격 라벨로 현재가치까지 추정으로 낮추는 구현이 나올 수 있다.

**Required fix.** 조건×지표 전파 표를 추가한다. 최소 행은 정상 공식, 추정 환산가, 미분류/공백/페이지 미완료/중복 의심, 사건 시점 가격 결측, 현재가치 0, 현재가치 음수, 조정 최종 목표 0 이하, 계산 버전 격리다. 각 셀에 새 값/마지막 정상값/가림/계산 불가와 성격·측정 시각을 적고, 서로 다른 시점의 값이 같은 카드 묶음으로 오인되지 않게 한다.

### FT-06 — 계획 비교값이 투자성과로 오인되지 않도록 하는 상시 문맥이 부족하다

**Severity: Medium**

**Evidence.** `남은 필요 등가수익률`에는 투자 추천이 아니라는 좋은 예시가 있고([EXPERIENCE.md:57](./EXPERIENCE.md#L57)), 보장·성공/실패 투자 언어도 금지한다([EXPERIENCE.md:61](./EXPERIENCE.md#L61)). 하지만 대시보드의 여섯 지표는 별도의 사용자-facing 묶음 정의 없이 나열되고([EXPERIENCE.md:36](./EXPERIENCE.md#L36), [EXPERIENCE.md:190](./EXPERIENCE.md#L190)–[196](./EXPERIENCE.md#L196)), `목표 수익 달성도`와 `계획 대비 차이`가 공식 투자성과처럼 읽히지 않도록 하는 인접 설명은 없다. 소스는 정확한 TWR/MWR/XIRR 및 투자성과 암시를 MVP에서 제외한다([source-extract.md:14](./.working/source-extract.md#L14)–[23](./.working/source-extract.md#L23), [source-extract.md:302](./.working/source-extract.md#L302)–[314](./.working/source-extract.md#L314)).

**Consequence.** 사용자가 입출금 조정 계획 비교를 실제 투자수익률 또는 검증된 성과로 해석해 의사결정에 사용할 수 있다. 이는 제품이 가장 피하려는 금융 오해다.

**Required fix.** 지표 묶음 제목과 첫 노출 설명에 `복리 계획 비교`임을 명시하고, “투자성과·계좌 수익률·투자 추천이 아닙니다”를 인접 배치한다. 각 비율 도움말에는 분모/기준 주기/외부 자금흐름 반영 방식과 음수·100% 초과 의미를 평이하게 설명한다. 이 문맥은 최초 방문뿐 아니라 결과 화면과 완료 결과에도 남아야 한다.

### FT-07 — 격리 시 마지막 정상값 유지와 계산 표시 중단의 우선순위가 불명확하다

**Severity: Medium**

**Evidence.** 대시보드 상태 표는 실패·오프라인·격리 시 마지막 정상 묶음을 유지하면서 “계산 표시 정지 범위는 가림”이라고 한 셀에 적는다([EXPERIENCE.md:115](./EXPERIENCE.md#L115)). 서비스 공지는 마지막 정상값 조회를 허용 가능한 범위에서 유지한다고 한다([EXPERIENCE.md:77](./EXPERIENCE.md#L77)). 소스는 공통 계산 문제에서 해당 계산 버전의 결과 표시를 중단하고, 범위 불명 시 신규 연결·동기화를 전역 중단하도록 구분한다([source-extract.md:253](./.working/source-extract.md#L253)–[265](./.working/source-extract.md#L265)).

**Consequence.** 계산 버전 격리 중에도 마지막 정상 파생지표가 보일지, 현재가치만 보일지, 모두 가릴지가 구현자 판단에 맡겨진다. 사용자는 숨김을 `0`이나 데이터 유실로, 남은 값을 현재 유효 계산으로 오해할 수 있다.

**Required fix.** 정지 유형별 표시 우선순위 표를 만든다. 연결 정지·동기화 정지·계산 버전 정지·광고 정지 각각에 대해 현재가치, 계획 파생값, 조정 이력, 수동 동기화, 완료 결과의 `표시/마지막 정상+시각/가림+이유`를 지정한다. 복구 뒤에는 검증 완료 시각과 새 스냅샷/계산 버전이 확정되기 전까지 이전 값을 최신으로 승격하지 않는다.

### FT-08 — 재계산 이력이 “변경됨”을 알리지만 무엇이 왜 바뀌었는지 비교 계약이 없다

**Severity: Medium**

**Evidence.** `history-row`는 지연 재계산 시 이전 근거를 덮지 않고 변경 표지와 새 버전 링크를 남긴다([DESIGN.md:180](./DESIGN.md#L180), [EXPERIENCE.md:75](./EXPERIENCE.md#L75)). UJ-3도 새 계산 버전과 변경 근거를 표시한다([EXPERIENCE.md:218](./EXPERIENCE.md#L218)). 그러나 이전/이후 값, 영향을 받은 기간·지표, 변경 원인, 발견 시각을 한 번에 비교하는 사양은 없다. 소스는 지연 사건이 사건 시점 이후 모든 값을 결정적으로 재계산하고 원 사건·버전·근거를 보존하도록 요구한다([source-extract.md:192](./.working/source-extract.md#L192)–[205](./.working/source-extract.md#L205)).

**Consequence.** 과거 숫자가 달라졌다는 사실은 보이지만 사용자가 정당한 재계산인지 조용한 데이터 변조인지 판단하기 어렵다. 장기 이용에서 기록의 불변성에 대한 신뢰가 약해진다.

**Required fix.** 버전 상세에 변경 원인 사건, 발견/반영 시각, 영향 시작 시점, 영향 지표, 이전 값→새 값, 사용한 가격 출처/추정 상태를 제공한다. 원 사건은 삭제·수정할 수 없는 읽기 전용 항목으로 유지하고 모든 후속 버전에서 역참조한다.

### FT-09 — 매우 큰 금액·비율의 보존은 선언됐지만 화면 넘침 처리 규칙이 없다

**Severity: Low**

**Evidence.** 음수와 100% 초과 값을 자르지 않고 `-0.00`을 정규화하는 원칙은 명확하다([EXPERIENCE.md:84](./EXPERIENCE.md#L84)–[87](./EXPERIENCE.md#L87), [EXPERIENCE.md:143](./EXPERIENCE.md#L143), [DESIGN.md:192](./DESIGN.md#L192)). 그러나 고정 28px 지표 글꼴과 카드 그리드([DESIGN.md:45](./DESIGN.md#L45)–[50](./DESIGN.md#L50), [DESIGN.md:160](./DESIGN.md#L160))에서 매우 큰 소수·백분율을 줄임표 없이 보존하는 줄바꿈/축약/전체값 접근 규칙이 없다.

**Consequence.** 구현이 값을 잘라내거나 과도한 축약 표기만 보여주면 부호·자릿수가 사라져 금융 의미가 변할 수 있다.

**Required fix.** 카드 값은 부호와 단위를 함께 유지하며 줄바꿈 또는 반응형 크기 조정을 허용하고, 축약 표기를 쓸 경우 포커스/탭 가능한 전체값과 스크린리더 전체값을 제공한다. 가로 스크롤이나 줄임표만으로 값을 숨기지 않는다.

## Preserved strengths

- 실패·키 오류를 `0`으로 바꾸지 않고 마지막 정상 스냅샷과 정확한 측정 시각을 유지한다([EXPERIENCE.md:93](./EXPERIENCE.md#L93), [EXPERIENCE.md:101](./EXPERIENCE.md#L101)–[105](./EXPERIENCE.md#L105)).
- 음수와 100% 초과를 중립적으로 보존하고 성과 색·축하·공포 모션을 금지한다([DESIGN.md:147](./DESIGN.md#L147)–[148](./DESIGN.md#L148), [DESIGN.md:192](./DESIGN.md#L192)).
- 연결 검증은 읽기 전용·UTA·UID/IP·실조회 조건을 단계별로 확인하고 실패한 자격정보를 저장하지 않는다([EXPERIENCE.md:113](./EXPERIENCE.md#L113), [EXPERIENCE.md:200](./EXPERIENCE.md#L200)–[209](./EXPERIENCE.md#L209)).
- 연결 해제 시 자격정보 삭제와 과거 기록의 읽기 전용 보존을 명확히 분리한다([EXPERIENCE.md:243](./EXPERIENCE.md#L243)–[250](./EXPERIENCE.md#L250)).
- 광고 결과와 동기화 결과를 분리하고 실패·거부·이탈 뒤 광고 재시청 없이 저장된 결과로 복구한다([EXPERIENCE.md:76](./EXPERIENCE.md#L76), [EXPERIENCE.md:118](./EXPERIENCE.md#L118), [EXPERIENCE.md:198](./EXPERIENCE.md#L198)).
- 계획·조정·완료 이력을 불변 기록으로 다루고 완료 뒤 현재가치와 완료 결과를 분리한다([EXPERIENCE.md:222](./EXPERIENCE.md#L222)–[231](./EXPERIENCE.md#L231)).
- 자동 재시도, 강제 새로고침, 보상형 동기화, 스트릭·푸시 압박을 금지해 복구를 강박적 행동과 분리한다([EXPERIENCE.md:129](./EXPERIENCE.md#L129)–[132](./EXPERIENCE.md#L132), [EXPERIENCE.md:181](./EXPERIENCE.md#L181)–[184](./EXPERIENCE.md#L184)).

## Release gate recommendation

FT-01~FT-04를 UX 최종화의 필수 수정으로 둔다. FT-05~FT-08은 구현 착수 전 상태·문구·이력 계약으로 닫고, FT-09는 핵심 화면 목업 및 320px/200% 확대 검증에서 확인한다. 특히 광고 공급자·동의·데이터 흐름과 캐시 정책이 미확정인 동안에는 광고와 오프라인 캐시를 제외한 베타만 신뢰 계약에 부합한다.

## Post-fix verification

### Revised verdict

**통과.** FT-01~FT-09의 UX 계약 결함은 모두 해결됐다. 수정된 스파인은 광고·개인정보 경계, 비지속 캐시 원칙, 지표 재현 계보, 삭제 상태, 지표별 성격 전파, 비성과 언어, 정지 우선순위, 재계산 변경 비교, 큰 숫자의 전체값 접근을 구현 가능한 수준으로 명시한다.

| Finding | Status | Verification evidence |
|---|---|---|
| FT-01 | Resolved | 동의 전 SDK/제3자 요청 금지, 거부·철회, 비개인화 한정, 중립 라우트 DOM·URL·제목·슬롯명 금지 목록, 미확정 시 광고 없는 출시가 추가됐다([EXPERIENCE.md:93](./EXPERIENCE.md#L93)–[98](./EXPERIENCE.md#L98)). |
| FT-02 | Resolved | 오프라인 금융값 표시를 제외하고 지속 브라우저 캐시·서비스 워커·공유 저장소 금지와 수동 복귀를 명시했다([EXPERIENCE.md:116](./EXPERIENCE.md#L116)–[118](./EXPERIENCE.md#L118)); 로그아웃·세션 만료·계정 삭제 후 뒤로 가기 복원도 차단한다([EXPERIENCE.md:73](./EXPERIENCE.md#L73)). |
| FT-03 | Resolved | 계산 근거 표면이 스냅샷 ID, `tc`, 측정 시각, 계산 버전, 조정 이력 버전·사건 링크와 불변 이전 버전을 연결한다([EXPERIENCE.md:39](./EXPERIENCE.md#L39), [EXPERIENCE.md:100](./EXPERIENCE.md#L100)–[102](./EXPERIENCE.md#L102), [EXPERIENCE.md:322](./EXPERIENCE.md#L322)–[329](./EXPERIENCE.md#L329)). |
| FT-04 | Resolved | 계정 삭제 전용 표면·흐름이 삭제 대상, 법률상 보존 범위·기간·목적, 진행·완료·부분 실패, 세션 종료와 재접근 차단을 다룬다([EXPERIENCE.md:45](./EXPERIENCE.md#L45), [EXPERIENCE.md:165](./EXPERIENCE.md#L165), [EXPERIENCE.md:312](./EXPERIENCE.md#L312)–[320](./EXPERIENCE.md#L320)). 미확정 정책은 숨기지 않고 출시 차단으로 등록됐다([EXPERIENCE.md:27](./EXPERIENCE.md#L27)). |
| FT-05 | Resolved | 조건별로 현재가치와 다섯 의존 지표의 공식·추정·마지막 정상·계산 불가·가림 전파 표가 추가됐고 혼합 시점/버전 묶음을 금지한다([EXPERIENCE.md:100](./EXPERIENCE.md#L100)–[114](./EXPERIENCE.md#L114)). |
| FT-06 | Resolved | 모든 첫 화면·결과·완료 결과의 묶음명을 `복리 계획 비교`로 고정하고 투자성과·계좌 수익률·추천이 아니라는 인접 문구를 요구한다([EXPERIENCE.md:61](./EXPERIENCE.md#L61), [EXPERIENCE.md:114](./EXPERIENCE.md#L114)). |
| FT-07 | Resolved | 신규 연결·동기화·계산 버전·광고 정지별 현재가치, 파생값, 이력, 사용자 행동과 복구 승격 조건이 분리됐다([EXPERIENCE.md:120](./EXPERIENCE.md#L120)–[129](./EXPERIENCE.md#L129)). |
| FT-08 | Resolved | 재계산 버전은 원인 사건, 발견·반영 시각, 영향 시작점·지표, 이전값→새값, 가격 출처·추정 상태를 보존하고 Flow 8에서 확인된다([EXPERIENCE.md:79](./EXPERIENCE.md#L79), [EXPERIENCE.md:322](./EXPERIENCE.md#L322)–[329](./EXPERIENCE.md#L329)). |
| FT-09 | Resolved | 긴 숫자의 부호·통화·백분율 보존, 시각 축약 시 전체값 열기·복사, 보조기술 전체값 제공을 명시했다([EXPERIENCE.md:189](./EXPERIENCE.md#L189)); 공백 없는 ID의 줄바꿈·전체값 접근도 정했다([DESIGN.md:159](./DESIGN.md#L159)). |

### Revised counts

| Severity | Open count |
|---|---:|
| Critical | 0 |
| High | 0 |
| Medium | 0 |
| Low | 0 |

### Remaining blockers

UX 스파인 자체의 미해결 금융 신뢰 결함은 없다. 다만 다음 소스 소유 결정·출시 검증은 여전히 외부 차단 조건이다.

- **계정 삭제 정책:** 법률상 보존 대상·기간·목적을 제품·개인정보 책임자가 확정해야 계정 삭제 UX와 베타 출시가 열린다([EXPERIENCE.md:27](./EXPERIENCE.md#L27), [EXPERIENCE.md:320](./EXPERIENCE.md#L320)).
- **광고 통합:** 공급자·공식 트리거·빈도·동의·실제 네트워크 데이터 흐름이 확정·검사되기 전에는 광고를 포함할 수 없다. 기본 경로는 광고 없는 베타이므로 핵심 제품 출시는 막지 않는다([EXPERIENCE.md:25](./EXPERIENCE.md#L25), [EXPERIENCE.md:98](./EXPERIENCE.md#L98)).
- **검증 실행:** 계정 삭제·세션 만료 후 뒤로 가기/캐시 재노출, 광고 동의 전 네트워크 무요청, 추정·격리 전파 조합, 320px/400% 확대의 긴 음수·100% 초과 값을 실제 구현 및 목업에서 검증해야 한다. 이는 구현 검증 게이트이며 추가 UX 결정 공백은 아니다.
