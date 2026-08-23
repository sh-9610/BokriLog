# BokriLog MVP PRD Addendum

이 문서는 [BokriLog MVP PRD](prd.md)의 계산·광고·소셜 인증 구현 인계사항을 보완한다. 제품 행동과 범위가 충돌하면 PRD가 우선하며, 이 문서는 하위 구현 세부만 소유한다.

## A. 계산·데이터 구현 인계사항

- 공식 현재가치는 Bybit UTA `totalEquity`이며 다른 잔액 필드와 혼용하지 않는다.
- Bybit 거래 로그의 `cashFlow`는 외부 입출금만 뜻하지 않으므로 값의 부호나 0 여부로 분류하지 않는다.
- 외부 자금흐름 판정은 검증된 사건 유형 허용목록과 UTA 경계 판정을 함께 사용한다.
- 거래소 사건 ID를 멱등 키로 사용하고 중첩 페이지·재조회·지연 사건을 고정 예제로 검증한다.
- 24시간 평가액을 수집하지 않으므로 MVP에서 정확한 TWR을 주장하지 않는다.
- MWR/XIRR은 완전한 외부 현금흐름 이력, 부호 조건과 수치해가 필요하므로 MVP에서 제외한다.
- 금액, 비율, API의 십진수 문자열은 이진 부동소수점이 아닌 정확한 십진수로 처리한다.
- 달력 주기 계산은 `t0`에 고정한 일·주·월 경계와 각 구간 실제 길이를 사용하고, 월말·윤년·부분 주기 가법성을 고정 예제로 검증한다.

## B. 광고 구현 인계사항

- 사용자가 요구한 형식은 보상형 광고가 아니라 사용자가 닫을 수 있는 전면광고다.
- 제품은 수동 동기화 성공 후 사용자당 30분에 최대 1회, `Asia/Seoul` 하루 최대 3회의 빈도 조건을 충족할 때만 광고 노출을 요청한다.
- 광고 시도는 공급자 요청 생성 시점에 원자적으로 기록하며 중복 요청·새로고침·결과 재진입으로 다시 요청하지 않는다.
- 광고 실패는 동기화 성공 상태나 결과 조회를 변경하지 않는다.
- 광고 공급자의 자동 타기팅 입력에 API 자격정보, 계정가치, 계획, 수익률, 자금흐름 또는 신뢰 상태를 제공하지 않는다.
- 개인 맞춤형·행태 기반 광고는 사용하지 않고 일반 화면 문맥만 사용하는 문맥형 광고로 한정한다.
- 광고 공급자는 구현 단계에서 선정한다. 유광고 공개 베타 전 전면광고 지원 범위, 개인정보 동의, 광고 차단·미채움 시 동작, 정책상 허용되는 트리거와 실제 소재의 키보드·스크린리더 접근성을 승인해야 한다.
- 실제 광고 네트워크 요청을 검사해 URL·페이지 제목·슬롯명·쿠키·공급자 식별자와 동의 상태를 포함한 데이터 흐름표를 확정한다.
- 광고 요청은 금융값과 신뢰 상태가 DOM에 없는 중립 전환 화면에서만 수행하고, 공급자가 대시보드 DOM 문맥을 수집해야 하면 채택하지 않는다.
- 실제 소재가 접근성 승인을 통과하지 못하거나 광고 영역 밖으로 키보드 포커스를 이동할 수 없으면 광고를 건너뛰고 저장된 최신 결과를 표시한다.

## C. 소셜 인증 구현 인계사항

- Google·네이버·카카오 모두 OpenID Connect(OIDC) Authorization Code 흐름과 PKCE를 적용하고 `state`, `nonce`, 리디렉션 URI, 발급자·대상·서명·만료 클레임을 검증한다.
- 외부 이메일이나 표시명이 아니라 `(issuer, subject)`를 제공자별 고유 식별자로 사용하고, 이를 BokriLog 내부 `user_id`에 연결한다.
- 이메일 일치 자동 병합은 금지한다. 기존 계정에 새 수단을 추가할 때는 현재 수단 재인증과 새 제공자 인증을 모두 요구한다.
- 제공자 토큰은 브라우저 저장소·URL·로그·분석·광고 도구에 남기지 않고 서버 측 보호 저장소에서 최소 범위·최소 기간으로 관리한다.
- 로그인 수단 연결·해제와 계정 삭제 감사기록은 법률상 필요한 최소 범위에 한해 사용자 데이터와 분리하고, 출시 전 확정한 목적·기간 동안만 보존한다. 토큰·금융 상세값은 남기지 않는다.

## D. 조사 근거

- Bybit Get Wallet Balance: https://bybit-exchange.github.io/docs/v5/account/wallet-balance
- Bybit Get Transaction Log: https://bybit-exchange.github.io/docs/v5/account/transaction-log
- Bybit Get API Key Information: https://bybit-exchange.github.io/docs/v5/user/apikey-info
- GIPS Standards Handbook: https://www.gipsstandards.org/standards/gips-standards-for-firms/gips-standards-handbook-for-firms/
- Microsoft XIRR: https://support.microsoft.com/en-us/excel/functions/xirr-function
- PostgreSQL Numeric Types: https://www.postgresql.org/docs/current/datatype-numeric.html
- Google AdSense Vignette Ads: https://support.google.com/adsense/answer/16531962
- Google Publisher Tag Web Interstitials: https://developers.google.com/publisher-tag/samples/display-web-interstitial-ad
- OWASP Authorization Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html
- OWASP Logging Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html
