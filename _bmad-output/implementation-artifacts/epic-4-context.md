# Epic 4 Context: 광고 동의와 관계없이 동기화 결과 확인하기

<!-- Compiled from planning artifacts. Edit freely. Regenerate with compile-epic-context if planning docs change. -->

## Goal

무료 공개 베타의 수익화 경로를 핵심 기능과 분리해 만든다. 사용자는 제3자 광고 데이터 흐름을 이해한 뒤 문맥형 광고 동의를 선택·철회하고, 수동 동기화가 성공해 공식 결과가 저장된 뒤에만 금융정보 없는 중립 전환 화면에서 닫을 수 있는 광고를 보거나 건너뛴다. 광고는 결과 조회의 성공 조건이 아니며, 비동의·빈도 제한·운영 정지·미채움·차단·오류·접근성 실패·이탈 어느 경우에도 이미 저장된 같은 결과로 즉시 이동한다(fail-open). 실제 광고 계정·ad unit·소재·개인정보·접근성 승인은 외부 출시 게이트이며, 승인 전 production adapter는 `DISABLED`이고 stub 시험만으로 유광고 출시 준비 완료를 선언하지 않는다.

## Stories

- Story 4.1: 문맥형 광고 동의를 선택하고 철회하기
- Story 4.2: 수동 동기화 성공 결과에 광고 자격과 빈도 제한 적용하기
- Story 4.3: 금융정보 없는 중립 화면에서 닫을 수 있는 광고 시도하기
- Story 4.4: 모든 광고 실패에서 저장된 결과로 fail-open하고 광고만 비활성화하기

## Requirements & Constraints

- **동의.** 설정 화면이 공급자, 비개인화 문맥형 범위, 광고망에 전달될 수 있는 범주(IP·UA·쿠키/저장소·URL·제목·동의/공급자 식별자)와 전달 금지 범주(금융·계획·신뢰·사용자 데이터)를 설명한다. 선택은 consent policy version·선택·시각을 CSRF·소유권·삭제 상태 확인 뒤 원자 저장하고, 철회는 다음 광고 요청부터 즉시 적용하며 자동 재동의를 만들지 않는다. 오프라인·저장 실패면 이전 선택을 유지하고 성공으로 표시하지 않는다. 비동의여도 연결·동기화·대시보드는 동일하게 동작한다.
- **동의 전 0건.** 명시적 동의 전에는 광고 SDK load·pixel·preconnect·제3자 네트워크 요청·쿠키/저장소 접근이 하나도 없어야 한다.
- **trigger.** 공식 결과가 원자 저장된 `manual` 동기화만 자격 후보다. 공식 trigger는 성공 뒤 중립 전환 화면의 `결과 보기` anchor 클릭 하나이며, 동기화 완료 자체·대시보드 진입·탭 재활성화·inactivity·뒤로 가기·실패·검토 필요·재전송은 광고를 만들지 않는다.
- **빈도 제한.** 서버가 사용자당 마지막 자격 시도 후 30분, `Asia/Seoul` 하루 최대 3회를 계산한다. reservation이 곧 노출 시도로 집계되고, cap reservation과 sync result reference를 원자 저장해 동시 탭 중 하나만 자격을 얻으며 같은 결과 재전송은 새 시도가 아니다. 공급자 자체 cap은 서버 cap 위에 추가로 적용한다. 자격 판정은 동의 유효성·`ADVERTISING` 운영 제한·공급자 활성 상태를 함께 본다. cap·제한·비동의·공급자 비활성은 광고 요청 없이 같은 저장 결과로 보내며 결과 접근을 막지 않는다.
- **데이터 경계.** 중립 route의 DOM·URL·제목·slot·targeting에 금융값·계획값·신뢰 상태·사용자/result 식별자를 넣지 않고 서버 보관 불투명 transition reference만 쓴다. 비개인화 문맥형만 허용하며 대시보드 DOM 문맥 분석을 요구하는 공급자는 쓰지 않는다. 오류 메시지·로그·분석·provider payload·telemetry label에도 사용자·결과·금융 식별을 싣지 않는다.
- **fail-open.** 빈 slot, no-fill, 차단, timeout 3초, render·접근성 실패, 동의 철회, 이탈·재진입, 중복·늦은 callback은 모두 binding된 같은 저장 결과로 즉시 이동한다. 결과 재계산·유실·빈 화면이 없고, 자동 재요청·다른 공급자 fallback·cap 우회가 없으며, 사용자에게 광고 실패 해결을 요구하지 않는다.
- **독립 정지.** 운영자는 광고만 제한할 수 있고 연결·동기화·계산·대시보드는 계속 동작한다. 광고가 Web Vitals 목표(p75 LCP 2.5초·INP 200ms·CLS 0.1)를 위협하면 성능 kill switch가 광고만 비활성화한다.
- **계측.** trigger·skip/failure reason·reservation·요청·실노출·닫기·저장 결과 도달을 분리 측정한다(광고 뒤 결과 미조회 이탈 15% 초과는 빈도·시점 재설계 신호). 허위 활동을 늘리는 자동 새로고침·강제 재시도·보상형 동기화를 유도하지 않는다.
- **삭제.** 동의·시도·빈도 제한·transition·멱등 데이터는 engagement deletion port와 멱등 삭제 시험을 데이터 도입과 함께 만든다. 사용자 귀속 데이터와 식별 없는 집계의 경계를 데이터 계약에 명시한다. 삭제 진행 뒤의 동의 변경·새 시도·늦은 callback이 데이터를 재생성하지 못해야 한다.
- **출시 게이트.** production 활성화는 실제 request field 검사, 개인정보 검토(광고·데이터 흐름이 바뀌면 재검토), 실제 소재의 키보드·스크린리더·닫기 검증, Google 계정 승인을 요구하며 코드·자동시험으로 대체하지 않는다. SDK·소재가 접근성에 영향을 주게 바뀌면 다시 시험한다.

## Technical Decisions

- 여덟 번째 모듈 `engagement`를 이 Epic이 처음 만든다. 동의, 수동 동기화 성공 trigger, 30분·일 3회 cap, provider adapter를 소유하고 consent·ad attempt·frequency cap 표를 자기 것으로 둔다. 다른 모듈에는 공개 API로만 닿고 Spring Modulith 경계 시험을 따른다. 광고를 결과 성공 조건으로 쓰는 의존을 만들지 않는다.
- 공급자 계약은 `AD-GPT-1.0`이다 — Google Ad Manager의 Google Publisher Tag(GPT) web interstitial. GPT는 중립 전환 route에서만 load하고 다른 모든 링크에는 interstitial 방지 속성을 둔다. 실제 network/ad-unit 승인·계약이 없으면 adapter는 `DISABLED`로 동작하고 결과를 즉시 표시한다.
- cap·reservation·시각 판정의 권위는 서버(PostgreSQL)이며 KST 하루 경계·30분 창·DST 무관성은 가상 시계 시험으로 고정한다. 광고 오류는 강한 신호가 아니다(자동 정지를 일으키지 않는다).
- 광고 정지는 `operations`의 durable restriction `ADVERTISING` 차원을 fail-closed로 읽는다. 새 운영 command(예: 성능 kill switch)가 필요하면 기존 운영 command 엔진·화면 상태 기계를 쓰고, 새 계측은 telemetry allowlist에 행을 먼저 더한다.
- 저장소의 현재 브라우저 경계(외부 스크립트·CDN 금지, CSP 비확장, 브라우저 저장소 쓰기 금지, 진입 화면 외부 요청 0건)와 GPT load·광고 저장소 사용은 충돌한다. 중립 route에 한정한 예외 범위와 동의 게이트는 해당 스토리 SPEC이 명시적으로 승인해야 하며, 새 화면 경로는 경로 맵·Nginx 허용 목록 등 기존 다섯 곳 계약을 함께 따른다.

## UX & Interaction Patterns

- 설정 화면이 로그인 수단·Bybit 연결과 함께 광고 동의 상태를 보여주고, 변경 중에는 그 제어만 잠그며 실패 시 기존 상태를 유지한다. 키보드·확대·낭독 환경에서 선택·철회가 가능해야 한다.
- `neutral-ad-transition`은 금융값·계획값·수익률·신뢰 상태가 없는 별도 표면이다. `광고` 표지와 iframe 밖 네이티브 `광고 닫기`가 DOM의 첫 조작이자 최초 포커스이며, 광고 콘텐츠·동기화 조작과 충분히 분리한다. 대시보드 카드의 입체 언어를 쓰지 않아 금융 결과로 오인되지 않게 한다. 모바일에서는 전체 화면이고 일반 탭 순서를 쓰며 모달일 때만 상위 문서에서 포커스를 순환한다.
- 닫기 뒤에는 저장된 최신 결과로 이동하고 갱신된 대시보드 제목으로 포커스를 돌린다. 재진입 시 광고를 다시 요구하지 않는다. 미동의·미채움이면 제3자 요청 없이 즉시 결과로 간다.
- 키보드 트랩·레이블 누락·로드 시간초과·보조기술 실패가 감지되면 광고를 건너뛴다. 200%·400% 확대, `prefers-reduced-motion`, 자동재생 음성·과도한 깜빡임 방지를 지킨다.
- 금지: countdown 닫기, 보상형 광고·동기화, 새로고침·임의 popup·당겨서 새로고침으로 광고 유도, 자동 재시도 루프. 카피는 "광고를 시청하면 결과가 열립니다"가 아니라 "광고를 건너뛰어도 최신 결과를 확인할 수 있습니다" 쪽이다. `복리 계획 비교`·비추천 고지 인접 배치 규칙은 그대로다.

## Cross-Story Dependencies

- 순서는 **4.1 동의 → 4.2 자격·빈도 제한 → 4.3 중립 화면·adapter → 4.4 단일 fail-open·광고 전용 정지**다. 4.2는 4.1의 동의 판독을, 4.3은 4.2의 reservation·result binding을, 4.4는 4.1~4.3 전체 경로를 전제한다.
- 다른 Epic: 수동 동기화 성공과 원자 발행 결과(Story 2.11)가 trigger의 원천이고, 운영 제한의 `ADVERTISING` 판독·command(Story 3.2)가 광고 정지를, 관측 계측 기반(Story 3.7)이 지표와 Web Vitals kill switch를 받친다. 사용자 귀속 쓰기는 Epic 1의 삭제 상태 계약을 따르고, 여기서 만든 engagement deletion port는 Epic 5의 계정 삭제 orchestration이 연결한다.
