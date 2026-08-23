# BokriLog MVP PRD 디스커버리: 제품 관행·수익률·예외·운영 기준

작성일: 2026-08-23  
목적: 기존 제품 브리프를 바꾸거나 기능 범위를 넓히지 않고, MVP PRD의 계산 정의와 인수 조건을 뒷받침할 최신 근거를 추출한다.

## 한눈에 보는 결론

1. 개인 투자 추적 제품은 보통 현재가치와 기간 성과를 함께 보여주고, 사용자가 거래·보유 정보를 수정하거나 삭제할 수 있게 한다. 성숙한 제품은 단순 손익, 기간 누적수익률, 연환산수익률, TWR, MWR/IRR을 서로 다른 지표로 구분한다.
2. BokriLog의 핵심은 범용 포트폴리오 분석이 아니라 **고정된 복리 계획선과 Bybit UTA 계정가치의 비교**다. 따라서 MVP에서 TWR·MWR을 새 기능으로 추가하기보다, 계획 수학과 자금흐름 조정 규칙을 명시하고 “투자성과 수익률”과 혼동되지 않게 이름 붙이는 것이 범위에 맞다.
3. 외부 현금흐름이 있는 성과를 정말 표시하려면 단순수익률로는 부족하다. 운용 자체를 보려면 TWR, 사용자가 실제로 경험한 자금 성과를 보려면 MWR/XIRR이 맞다. 그러나 BokriLog는 24시간 평가액을 수집하지 않으므로 정확한 TWR을 계산할 자료가 부족하다. MWR/XIRR도 입출금 이력의 완전성이 확인된 경우에만 가능하다.
4. Bybit `transaction-log.cashFlow`는 입출금뿐 아니라 포지션 청산 손익과 세션 정산도 포함한다. 따라서 `cashFlow != 0`을 외부 자금흐름 판정으로 쓰면 안 된다. 거래 유형과 UTA 경계를 기준으로 허용 목록을 두고, 미분류 사건은 계산을 “검토 필요” 상태로 내려야 한다.
5. 0 또는 음수 기준액, 같은 날짜 사건, 뒤늦게 발견된 사건, 중복 API 페이지, 비-USDT 환산가 결측, 삭제·수정에 따른 과거 재계산, XIRR 비수렴을 정상적인 도메인 상태로 다뤄야 한다. 정의되지 않은 값은 `0%`가 아니라 “계산 불가/근거 부족”으로 표시해야 한다.
6. 출시형 개인 프로젝트의 최소선은 화려한 분석이 아니라 계산 재현성, 데이터 격리, 읽기 전용 키 검증, 비밀정보의 전송·저장 보호, 민감정보 비로그, 동기화 실패의 명시, 사고 정지 장치, 복구 가능한 백업이다.

## 1. 현재 제품 관행과 MVP에 주는 의미

### 1.1 관찰된 관행

- Google Finance의 포트폴리오는 종목, 수량, 매수일, 매수가를 입력하고 여러 매수분을 추가하며, 보유 내역을 수정·삭제하고 현재 가치와 성과를 확인하게 한다. 사용자가 포트폴리오 데이터를 편집·삭제할 통제권을 강조한다. [Google Finance 도움말](https://support.google.com/websearch/answer/16490185)
- Portfolio Performance는 전체 포트폴리오의 기간을 선택한 뒤 누적 TTWROR, IRR, 절대 변화, 외부 현금흐름을 제외한 변화량을 서로 별도 KPI로 표시한다. 즉 하나의 “수익률” 숫자로 모든 의미를 덮지 않는다. [Portfolio Performance 성과 화면](https://help.portfolio-performance.info/en/reference/view/reports/performance/)
- Sharesight는 성과 보고서의 퍼센트가 총 수익률이 아니라 **연환산수익률**임을 설명하고, 보고 시작일이 매수일 이후라면 실제 매수가가 아니라 시작일 시장가치를 기준으로 쓴다고 명시한다. 지표 이름, 기간, 기준값을 함께 보여주는 것이 숫자 오해를 줄이는 관행이다. [Sharesight Performance Report](https://help.sharesight.com/us/performance_report/)
- Google Finance와 Portfolio Performance는 수정과 삭제를 기본 조작으로 제공한다. 투자 기록은 정정이 빈번하므로 “한번 저장하면 고정”보다, 정정 후 파생값 전체를 다시 계산하고 영향 범위를 보여주는 방식이 일반적이다. [Google Finance 도움말](https://support.google.com/websearch/answer/16490185), [Portfolio Performance 투자 계획](https://help.portfolio-performance.info/en/reference/view/accounts/investment-plans/)

### 1.2 BokriLog에 적용할 최소 관행

MVP에 포함할 가치가 있는 관행은 다음뿐이다.

- 모든 퍼센트에 **지표명·대상 기간·기준 시점**을 함께 표시한다.
- “계획 대비 차이”, “목표 달성도”, “남은 필요 등가수익률”을 투자성과 수익률과 구분한다.
- 외부 자금흐름과 계정 내부 손익을 구분하고, 자금흐름 수정·삭제 시 이후 계획선과 파생지표를 결정적으로 재계산한다.
- 현재가치의 측정 시각, 동기화 성공/실패, 입력 신뢰도, 추정 여부를 숫자와 함께 표시한다.
- 삭제 전 영향 범위를 알리고 확인을 받되, 삭제가 끝나면 오래된 계산 결과를 남기지 않는다.

다음 관행은 존재하지만 BokriLog MVP에는 넣지 않는 것이 브리프와 일치한다.

- 종목별 보유·매수 lot·실현손익·세금 보고
- 자산배분, 벤치마크 비교, 위험지표, 최대 낙폭
- 다중 포트폴리오·다중 거래소
- 자동매매, 리밸런싱, 추천
- 완전한 TWR/MWR 성과 분석 화면

## 2. 수익률 용어와 계산 정의

### 2.1 단순수익률

외부 현금흐름이 없는 한 보유기간에서:

```text
R_simple = E / B - 1
```

- `B`: 기간 시작가치
- `E`: 기간 종료가치
- 결과는 해당 기간 전체의 누적수익률이며 연율이 아니다.
- `B = 0`이면 정의되지 않는다.
- `B > 0`, `E = 0`이면 `-100%`다.
- `B > 0`, `E < 0`이면 수학적으로 `-100%`보다 작을 수 있으나, 레버리지 계정의 음수 equity는 일반 소비자에게 오해 가능성이 높아 원자료 상태와 경고를 함께 보여야 한다.

Portfolio Performance도 거래의 단순수익률을 `(Exit Value / Entry Value) - 1`로 설명한다. [Portfolio Performance 거래 성과](https://help.portfolio-performance.info/en/reference/view/reports/performance/trades/)

### 2.2 기간수익률과 기간 손익

“기간수익률”은 기간 경계가 명확해야 한다. 시작일 이후의 성과를 측정할 때는 매수가가 아니라 기간 시작 평가액을 쓰는 관행이 있다. [Sharesight Performance Report](https://help.sharesight.com/us/performance_report/)

외부 현금흐름이 있다면 다음 금액은 유용하지만 엄밀한 성과율은 아니다.

```text
Adjusted profit amount = E - B - NetExternalFlow
```

- `NetExternalFlow = 입금 합계 - 출금 합계`로 정의할 수 있다.
- 이 금액을 단순히 `B`로 나눈 값은 현금흐름의 시점을 반영하지 않으므로, “현금흐름 조정 손익률”처럼 제한된 이름과 설명이 필요하다.
- 현금흐름이 크거나 기간 중간에 발생하면 TWR 또는 MWR을 써야 한다.

### 2.3 연환산수익률

현금흐름이 없는 누적수익률 `R`을 `d`일 보유했을 때, 365일 기준 연환산은:

```text
R_annualized = (1 + R)^(365 / d) - 1
```

Portfolio Performance도 이 공식을 사용한다. [Portfolio Performance 거래 성과](https://help.portfolio-performance.info/en/reference/view/reports/performance/trades/)

필수 경계 규칙:

- `d <= 0`: 계산 불가.
- `1 + R < 0`: 실수 범위에서 일반 연환산 계산 불가.
- `1 + R = 0`: 수학적 결과는 `-100%`지만, 매우 짧은 기간의 파산 상태를 연율로 강조하는 것은 오해를 키울 수 있으므로 누적 `-100%`와 기간을 우선 표시한다.
- 짧은 기간의 연환산은 극단적으로 커질 수 있다. MVP가 표시한다면 누적수익률과 보유일수를 반드시 병기한다.
- 연환산은 기간수익률과 다른 숫자이므로 라벨에 `연환산`을 생략하지 않는다.

### 2.4 시간가중수익률(TWR)

TWR은 입출금이 일어난 시점에서 기간을 나누고 각 하위기간 수익률을 기하 연결한다.

```text
r_i = E_i / (B_i + CF_i) - 1        # 현금흐름이 하위기간 시작에 발생한다고 둘 때
R_TWR = product(1 + r_i) - 1
```

실제 제품은 유입을 일초, 유출을 일말로 두는 일별 변형을 쓰기도 한다.

```text
r_day = (MVE + CF_out) / (MVB + CF_in) - 1
R_TWR = product(1 + r_day) - 1
```

TWR의 목적은 외부 입출금 영향을 중립화해 운용 자체의 성과를 보는 것이다. GIPS는 진정한 TWR에 현금흐름 시점의 평가와 하위기간 기하 연결이 필요하다고 설명한다. [GIPS Standards Handbook](https://www.gipsstandards.org/standards/gips-standards-for-firms/gips-standards-handbook-for-firms/), [Portfolio Performance TWR 설명](https://help.portfolio-performance.info/en/concepts/performance/time-weighted/)

**BokriLog MVP 영향:** 대시보드 진입·탭 재활성화·수동 새로고침 때만 평가액을 얻고 24시간 수집하지 않으므로, 모든 외부 현금흐름 직전·직후의 평가액이 보장되지 않는다. 따라서 현재 범위에서는 “정확한 TWR”을 공식 지표로 주장하지 않는 것이 안전하다. 추후 충분한 평가 스냅샷이 확보되어도 별도의 계산 SPEC과 검증이 필요하다.

### 2.5 금액가중수익률(MWR)과 XIRR

MWR은 현금흐름의 금액과 시점을 반영하며, 불규칙한 날짜의 현금흐름에는 XIRR 형태가 흔하다. 부호 관례를 “사용자가 투자한 돈은 음수, 회수한 돈과 종료 평가액은 양수”로 두면 `r`은 다음 식을 만족한다.

```text
0 = sum(CF_i / (1 + r)^((date_i - date_0) / 365))
```

- 시작 투자와 추가 입금: 음수
- 출금: 양수
- 측정 종료일의 남은 계정가치: 양수 terminal cash flow로 추가
- 결과: 연환산 내부수익률

Microsoft XIRR은 불규칙한 현금흐름을 대상으로 하며, 적어도 하나의 양수와 하나의 음수 현금흐름이 필요하고, 365일 기준과 반복 계산을 사용한다. 유효하지 않은 날짜, 시작일보다 앞선 날짜, 비수렴은 오류가 된다. [Microsoft XIRR 공식 문서](https://support.microsoft.com/en-us/excel/functions/xirr-function)

**BokriLog MVP 영향:** 완전한 외부 입출금 내역과 현재 `totalEquity`가 있으면 MWR은 TWR보다 자료 요구가 적다. 그러나 브리프의 핵심 지표가 아니며, 거래소 사건 분류가 불완전하거나 XIRR이 비수렴할 수 있으므로 MVP 공식 지표에 자동 추가하지 않는다. Later에서 도입한다면 `수익률을 계산할 근거 부족`, `부호 조건 불충족`, `해 없음/비수렴`을 서로 구분해야 한다.

### 2.6 BokriLog 계획 수학과 “수익률”의 경계

BokriLog가 MVP에서 반드시 정의해야 할 것은 포트폴리오 TWR/MWR보다 다음 계획 지표다.

- 주기별 목표율과 복리 주기의 시간 경계
- 특정 시각의 경과 완료 주기 수 또는 부분 주기 처리 방식
- 최초 계획금액과 외부 자금흐름이 계획선에 반영되는 시점
- 오늘 계획금액, 조정 최종 목표, 현재 차이, 남은 필요 등가수익률
- 계획 종료일 이후의 표시 규칙

이 값들은 사용자가 실제로 번 투자수익률이 아니라 **계획과 현재 상태의 수학적 비교**다. PRD에는 “성과”, “수익률” 같은 포괄어 대신 `목표 주기율`, `남은 필요 등가수익률`, `계획 대비 차이`라는 정확한 명칭을 유지하는 편이 좋다.

## 3. Bybit 데이터가 만드는 핵심 예외

### 3.1 공식 현재가치

Bybit는 UTA의 `totalEquity`를 각 자산 equity의 USD 가치 합계로 정의한다. 여기에는 지갑잔액뿐 아니라 미실현손익과 옵션 가치 등이 반영된다. 극단적 변동성에는 응답 지연이나 데이터 전달 지연이 있을 수 있다고 명시한다. [Bybit Get Wallet Balance](https://bybit-exchange.github.io/docs/v5/account/wallet-balance)

PRD 영향:

- 공식 현재가치는 응답에서 받은 decimal 문자열 그대로 보존하고, 측정 시각과 API 응답 성공 여부를 함께 기록한다.
- 오래된 성공값을 최신값처럼 보이면 안 된다. 마지막 성공 시각과 현재 동기화 상태를 분리한다.
- `totalWalletBalance`, `availableBalance`, 개별 코인 `equity`를 `totalEquity`와 혼용하지 않는다.
- isolated margin 등 일부 모드에서 계정 전체 필드가 적용되지 않을 수 있으므로 지원 모드 검증 실패 시 계획 생성을 막거나 검토 필요로 둔다.

### 3.2 외부 자금흐름 오분류 위험

Bybit 거래 로그의 `cashFlow`는 다음을 모두 포함할 수 있다.

- 포지션 청산 시 미실현손익의 실현손익 전환
- USDC 파생상품의 세션 정산
- 계정 간 transfer in/out

또한 `change = cashFlow + funding - fee`이고 거래 로그 조회는 한 요청의 시간 범위가 최대 7일, 페이지 크기가 최대 50이며, 전체 지원 이력은 최대 2년이다. 변동성 구간에는 지연될 수 있다. [Bybit Get Transaction Log](https://bybit-exchange.github.io/docs/v5/account/transaction-log)

PRD 영향:

- `cashFlow` 필드의 부호나 0 여부만으로 UTA 외부 입출금을 판정하지 않는다.
- UTA 경계를 실제로 넘는 명시적 transaction type의 허용 목록을 계산 버전과 함께 관리한다.
- 알 수 없는 신규 유형, 중복·누락 의심, 시간 범위 공백, 페이지 미완료가 있으면 공식 계획 계산에 반영하지 않고 검토 필요 상태를 낸다.
- 거래소 고유 `id`를 멱등 키로 삼고, 재조회·페이지 중첩에도 한 번만 반영한다.
- 사건이 늦게 발견되거나 과거에 삽입되면 그 시점 이후 계획선과 모든 파생값을 재계산한다.
- API가 최대 2년만 지원하므로 “과거 계획 자동 복원 없음”은 타당하다. 최초 정상 연결 이후의 수집 완전성을 서비스가 책임지는 경계를 명시한다.

### 3.3 API 키 적격성과 만료

Bybit 키 정보 API는 `readOnly=1`을 읽기 전용, `readOnly=0`을 읽기·쓰기로 정의하고, UTA 여부, UID, 바인딩 IP, 만료일/남은 유효일을 제공한다. IP가 없거나 비밀번호가 변경된 키에는 만료 관련 필드가 적용될 수 있다. [Bybit Get API Key Information](https://bybit-exchange.github.io/docs/v5/user/apikey-info)

PRD 영향:

- `readOnly == 1`, 지원 UTA, 실제 지갑 조회 성공을 모두 만족해야 연결 성공이다.
- 쓰기 키는 저장 전에 연결 시도만 거부하고, 비밀값과 입력 전문을 로그에 남기지 않는다.
- 키 상태 변경·만료·UID 불일치는 “현재가치 0”이 아니라 연결 오류다.
- IP 제한 없음은 신뢰 상태와 베타 정책에 반영하되, 브리프의 Day 90 정책 밖으로 기능을 넓히지 않는다.

## 4. 계산·데이터 입력의 예외 상황

다음은 구현 세부가 아니라 관찰 가능한 제품 규칙으로 PRD에 들어갈 가치가 있다.

### 4.1 숫자와 정밀도

- 금액과 비율 입력은 유한한 decimal 숫자만 허용한다. `NaN`, 무한대, 지수 폭주, 빈 문자열을 거부한다.
- 금액과 API decimal 문자열은 이진 부동소수점이 아니라 정확한 decimal 표현으로 저장·계산한다. PostgreSQL도 monetary amount처럼 정확성이 필요한 값에 `numeric`을 권장하며, `real`/`double precision`은 일부 값을 근사 저장한다고 설명한다. [PostgreSQL Numeric Types](https://www.postgresql.org/docs/current/datatype-numeric.html)
- 저장 정밀도와 표시 정밀도를 분리한다. 화면에서 USD 금액은 기본 2자리, 비율은 기본 2자리로 반올림할 수 있지만, 작은 값이나 경계 판정에는 원 정밀도를 사용한다.
- 표시 반올림값으로 다음 계산을 이어가지 않는다. 합계와 차이를 원 정밀도로 계산한 뒤 마지막 표시 단계에서 한 번 반올림한다.
- 반올림 모드와 소수 자릿수를 계산 SPEC에 고정한다. 서로 다른 런타임 기본 반올림에 맡기지 않는다.
- `-0.00`은 표시 전에 `0.00`으로 정규화하되, 실제 음수 손익을 0으로 만드는 허용오차를 넓게 두지 않는다.

### 4.2 0·음수 자산과 정의되지 않은 비율

- 시작 또는 분모 값이 0이면 나눗셈 기반 퍼센트는 계산 불가다.
- 현재 `totalEquity = 0`은 정상 API 응답과 연결 실패를 구분한다. 정상 0은 값이며, 실패는 상태다.
- `totalEquity < 0`은 저장 가능하더라도 계획 달성도·필요 복리율이 실수 범위에서 정의되지 않을 수 있다. 원 금액과 경고를 보여주고 정의되지 않은 파생 퍼센트를 숨긴다.
- 목표 기준금액 또는 남은 목표가 0 이하가 되는 조합은 도메인 규칙을 별도로 정해야 하며, 임의로 0%나 100%를 할당하지 않는다.
- XIRR에 양·음 현금흐름이 모두 없거나 수치해가 없으면 “0%”가 아니라 계산 불가다.

### 4.3 날짜·시간대·기간 경계

- 거래소 사건은 UTC millisecond timestamp를 원본으로 저장한다.
- 사용자의 “오늘”, 복리 주기 경계, 계획 종료일 표시는 계획에 고정된 IANA 시간대(예: `Asia/Seoul`)로 평가한다. 서버나 브라우저의 임시 기본 시간대를 계산 규칙으로 쓰지 않는다.
- 날짜만 저장하는 계획 경계와 순간을 나타내는 거래 timestamp를 구분한다. JavaScript는 timezone 없는 date-only 문자열을 UTC로 해석하고 timezone 없는 date-time은 로컬로 해석하므로, 암묵 파싱은 날짜 이동 버그를 낳을 수 있다. [MDN Date](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Date)
- 같은 timestamp 또는 같은 계획일의 여러 외부 현금흐름은 안정적인 키로 정렬하고 합산한다. 그날 계획 반영을 “일초” 또는 “사건 시각부터” 중 하나로 고정한다.
- 미래 시각의 거래소 사건, 종료보다 앞선 시작, 0일 기간, 종료 후 사건을 각기 명시적으로 처리한다.
- 날짜 수정·시간대 규칙 변경은 이후 모든 계획 포인트에 영향을 주므로 계산 버전 변경으로 관리한다.

### 4.4 생성·수정·삭제·중복

- 동일 거래소 사건 ID의 재수집은 새 자금흐름을 만들지 않는다.
- 사용자가 수동으로 확인·분류한 사건과 원 API 사건의 연결을 보존한다.
- 과거 사건 수정 또는 삭제 시 영향받는 날짜 이후의 계획금액, 최종 목표, 차이, 필요 등가수익률을 원자적으로 재계산한다.
- 삭제 전 사건 일자·금액·영향 지표를 보여주고 확인을 받는다.
- 삭제 완료 후 캐시·대시보드·내보내기 어디에도 이전 파생값이 남지 않는다.
- 동기화 도중 일부 페이지만 성공하면 부분 결과를 공식 상태로 승격하지 않는다. 마지막 완전 성공 상태는 보존하되 “최신 아님”을 표시한다.

### 4.5 비-USDT 흐름과 환산 결측

- 원자산 수량, 원자산 코드, 사건시각, 사용한 USD 환산가, 환산가 출처, 추정 여부를 함께 보존한다.
- 사건시각 가격이 없으면 임의의 현재가로 확정하지 않는다. 브리프대로 추정 상태를 유지하고 공식 계산 신뢰도를 낮춘다.
- 나중에 더 나은 가격이 확보되면 재계산하되, 값이 바뀐 사실과 계산 버전을 추적할 수 있어야 한다.
- 환율/가격 반올림으로 0이 된 작은 흐름을 소실시키지 않는다.

## 5. 출시형 개인 프로젝트의 최소 비기능 기준

아래 수치는 대규모 금융기관 기준이 아니라 공개 베타와 1인 운영을 위한 현실적인 최소선이다. 출처에서 직접 정한 BokriLog 수치가 아니라, 공식 보안·웹 품질 지침과 브리프의 안전 목표를 바탕으로 한 PRD 권고다.

### 5.1 계산 신뢰성

- 동일 입력·계산 버전은 언제나 동일 결과를 낸다.
- 모든 공식 지표는 입력 snapshot ID, 계산 버전, 기준시각으로 재현 가능하다.
- 정상·0·음수·초소액·대규모·동일일 다중 사건·뒤늦은 사건·중복·삭제·기간 종료를 포함한 고정 예제(golden cases)를 둔다.
- 배포 전 공식 계산 예제와 회귀 테스트 통과율 100%를 요구한다.
- 정의되지 않은 계산과 데이터 부족을 값 `0`으로 대체하지 않는다.
- 입력 신뢰도, 계산 성격, 신선도를 독립 상태로 유지한다.

### 5.2 동기화와 복원력

- 브리프의 동기화 성공률 95% 이상을 그대로 성공 기준으로 둔다. 분모는 실제 시도이며 사용자 취소, 거래소 장애 등 제외 규칙을 PRD에 고정한다.
- 재시도는 멱등이어야 하며 일부 페이지 성공을 완전 성공으로 기록하지 않는다.
- 마지막 성공값, 마지막 성공 시각, 가장 최근 시도 결과를 별도 표시한다.
- 외부 API 오류 때 계산 화면이 조용히 잘못된 값으로 바뀌지 않고, 마지막 검증값과 stale 상태로 저하된다.
- 사용자·연결·계획·자금흐름·계산 상태를 복구할 백업과 실제 복원 절차를 두고 베타 전 1회 이상 복원 시험을 한다.

### 5.3 성능과 사용성

- 핵심 화면은 일반 모바일·데스크톱에서 Core Web Vitals의 “good” 기준을 목표로 한다: 75번째 백분위에서 LCP 2.5초 이하, INP 200ms 이하, CLS 0.1 이하. [web.dev Web Vitals](https://web.dev/articles/vitals)
- 동기화가 오래 걸릴 때 빈 화면으로 막지 않고 진행·실패·마지막 성공 시각을 보여준다.
- 계산 결과는 동기화 완료 후 즉시 일관되게 갱신되고, 서로 다른 카드가 다른 snapshot을 표시하지 않는다.
- 베타 표본이 작아 현장 75백분위가 불안정하면 출시 전 실험실 측정과 실제 요청 latency를 함께 기록한다.

### 5.4 보안과 개인정보

- 모든 요청에서 사용자 소유권을 서버 측에서 검증하고, 기본 거부·최소 권한을 적용한다. OWASP는 모든 요청의 권한 검증, 기본 거부, 추측 가능한 ID의 접근 차단을 권고한다. [OWASP Authorization Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html)
- API Key와 Secret은 TLS로 전송하고 저장 시 강한 암호화와 키 분리를 적용한다. 화면에는 마스킹하고 로그·분석·오류 메시지에 포함하지 않는다.
- 비밀번호를 직접 운영한다면 승인된 단방향 password hashing으로 salt와 cost factor를 적용한다. [OWASP ASVS Authentication](https://github.com/OWASP/ASVS/blob/master/4.0/en/0x11-V2-Authentication.md)
- 인증 성공/실패, 권한 실패, 입력 검증 실패, 연결 생성·삭제, 비상정지 동작은 감사 가능하게 기록하되 세션 토큰, 접근 토큰, 비밀번호, API 비밀, 금융 상세값은 로그에서 제외·마스킹한다. [OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html)
- 날짜·금액·열거값을 구문과 업무 의미 양쪽에서 서버 검증한다. OWASP는 날짜 형식 같은 구문 검증과 시작일이 종료일보다 앞서는지 같은 의미 검증을 모두 요구한다. [OWASP Input Validation Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Input_Validation_Cheat_Sheet.html)
- 사용 목적에 필요한 최소 개인정보만 수집하고, 연결 해제 시 자격정보를 삭제하며, 계정 삭제·데이터 제거 경로를 제공한다. 데이터 최소화와 보유 제한은 보편적인 개인정보 원칙이다. [European Commission GDPR principles](https://commission.europa.eu/law/law-topic/data-protection/information-business-and-organisations/principles-gdpr_en)
- 금융정보를 광고 타기팅·광고망 payload·분석 도구로 보내지 않는다는 브리프 약속을 기술적으로 검증 가능한 요구사항으로 바꾼다.

### 5.5 운영 안전성

- 신규 연결, 동기화, 계산 표시를 독립적으로 정지할 수 있어야 한다.
- 읽기 전용 검증 실패·자격정보 노출 의심·교차 사용자 접근·공통 계산 오류에는 브리프의 자동/수동 정지 시간 목표를 적용한다.
- 핵심 경보에는 소유자, 대응 절차, 복구 조건이 있어야 한다. 단순히 로그를 쌓는 것으로 끝내지 않는다.
- Bybit API 변경내역과 transaction type enum을 정기 점검하고 미인식 유형은 기본 거부한다.

## 6. MVP와 Later의 명확한 분리 권고

### MVP에 남길 것

- 읽기 전용 Bybit UTA 연결과 적격성 검증
- 공식 `totalEquity` snapshot과 신선도 표시
- 고정된 최초 계획, 확인된 외부 자금흐름에 의한 계획 조정
- 계획금액, 현재 차이, 조정 최종 목표, 남은 필요 등가수익률, 목표 달성도
- 계산식·기간·시간대·반올림·0/음수·종료 규칙의 명시
- 미분류 사건, API 공백, 비-USDT 가격 결측, stale snapshot의 신뢰 상태
- 수정·삭제·중복·뒤늦은 사건에 대한 재계산
- 보안, 동기화, 감사, 비상정지, 백업·복원 최소선

### Later로 분리할 것

- 공식 TWR/TTWROR 성과 지표
- MWR/XIRR 성과 지표
- 종목·전략·상품별 성과 분해
- 실현/미실현 손익, 수수료·세금 보고
- 벤치마크·위험·자산배분 분석
- 상시 백그라운드 수집과 알림
- 준실시간 websocket 추정값
- 다중 거래소·다중 통화 포트폴리오 분석

Later 항목을 PRD 본문 기능 요구사항에 “준비” 요구로 끼워 넣지 않는 것이 중요하다. MVP에는 나중의 TWR/MWR을 위해 24시간 가격 이력을 미리 수집하는 숨은 범위를 두지 않는다. 다만 현재 기능 자체의 감사·재현에 필요한 원본 사건, snapshot, 계산 버전은 보존한다.

## 7. PRD에 바로 반영할 결정 포인트

다음 질문은 구현 전에 답이 하나로 고정되어야 한다.

1. 복리 주기 경계는 계획 시작의 시각을 반복하는가, 사용자의 로컬 자정 기준인가?
2. 부분 주기에 계획금액을 연속 복리로 보간하는가, 완료된 주기만 반영하는가?
3. 외부 입금·출금을 계획선에 사건 순간부터 반영하는가, 해당 로컬 일 시작부터 반영하는가?
4. 출금으로 조정 원금이나 목표가 0 이하가 될 때 계획을 계속할 수 있는가, 종료·검토 상태로 전환하는가?
5. 계획 종료일에 마지막 snapshot이 없으면 마지막 검증값을 쓰는가, 동기화를 요구하는가?
6. 계획 종료 후에는 결과를 고정하는가, 현재가치만 계속 갱신하되 완료 결과와 분리하는가?
7. 비-USDT 환산 추정이 있는 경우 어떤 지표까지 보여주고 어떤 지표를 공식 계산에서 제외하는가?
8. 지원하는 Bybit 외부 자금흐름 transaction type의 명시적 허용 목록은 무엇인가?
9. 표시 자릿수와 반올림 모드는 무엇인가?
10. 브리프의 “전체 목표 수익 달성도” 분모와 0·음수 목표에서의 동작은 무엇인가?

Fast path라면 위 항목에 합리적 `[ASSUMPTION]`을 붙여 초안을 완성할 수 있다. 다만 1–4와 8은 계산 결과를 크게 바꾸므로 UX·아키텍처 단계 전 반드시 확정해야 한다.

## 출처 목록

### 수익률·제품 관행

- [GIPS Standards Handbook for Firms](https://www.gipsstandards.org/standards/gips-standards-for-firms/gips-standards-handbook-for-firms/)
- [Microsoft XIRR 공식 문서](https://support.microsoft.com/en-us/excel/functions/xirr-function)
- [Portfolio Performance: Performance](https://help.portfolio-performance.info/en/reference/view/reports/performance/)
- [Portfolio Performance: Time-Weighted Rate of Return](https://help.portfolio-performance.info/en/concepts/performance/time-weighted/)
- [Portfolio Performance: Trades](https://help.portfolio-performance.info/en/reference/view/reports/performance/trades/)
- [Sharesight Performance Report](https://help.sharesight.com/us/performance_report/)
- [Google Finance 포트폴리오 도움말](https://support.google.com/websearch/answer/16490185)

### Bybit

- [Get Wallet Balance](https://bybit-exchange.github.io/docs/v5/account/wallet-balance)
- [Get Transaction Log](https://bybit-exchange.github.io/docs/v5/account/transaction-log)
- [Get API Key Information](https://bybit-exchange.github.io/docs/v5/user/apikey-info)
- [Different Account Modes](https://bybit-exchange.github.io/docs/v5/acct-mode)

### 데이터·웹 품질·보안

- [PostgreSQL Numeric Types](https://www.postgresql.org/docs/current/datatype-numeric.html)
- [MDN JavaScript Date](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Date)
- [web.dev Web Vitals](https://web.dev/articles/vitals)
- [OWASP Authorization Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html)
- [OWASP Authentication Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html)
- [OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html)
- [OWASP Input Validation Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Input_Validation_Cheat_Sheet.html)
- [European Commission: GDPR principles](https://commission.europa.eu/law/law-topic/data-protection/information-business-and-organisations/principles-gdpr_en)
