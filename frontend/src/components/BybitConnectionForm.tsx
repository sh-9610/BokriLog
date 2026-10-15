import {
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type ReactElement,
  type SyntheticEvent,
} from 'react'

import { connectionContent } from '../content/connection'
import { type SubmitOutcome } from '../session/useBybitConnection'
import styles from './BybitConnectionForm.module.css'

/** 폼 하나가 다루는 두 필드. 서버에 나가는 값도 이 둘뿐이다. */
type FieldName = 'apiKey' | 'apiSecret'

interface BybitConnectionFormProps {
  /** 서버가 준 확인 문구. 화면이 <b>그대로</b> 렌더하고 제출이 같은 값을 되보낸다. */
  readonly confirmation: string
  /** 요청이 도는 중인가. */
  readonly pending: boolean
  /**
   * 검증과 저장.
   *
   * 값이 셋이다. `ignored`는 훅의 가드가 막아 요청이 나가지 않았다는 뜻이며, 그때 Secret을 지우면
   * 사용자는 아무 요청도 나가지 않았는데 값을 잃는다.
   */
  readonly onSubmit: (apiKey: string, apiSecret: string) => Promise<SubmitOutcome>
  /** 폼을 닫고 준비가 만든 것을 되돌린다. */
  readonly onCancel: () => void
  /** 서버가 거절했다면 그 문장. 없으면 `null`이다. */
  readonly rejection: string | null
}

/**
 * 자격정보 입력 폼. <b>이 저장소의 첫 텍스트 입력이다.</b>
 *
 * DESIGN.md의 {@code secure-input-form} 계약을 지킨다 — 영구 라벨, 필드마다의 도움말, 필드 오류,
 * 오류 요약, 붙여넣기 허용, 그리고 <b>실패 시 Secret만 지우고 비밀 아닌 입력과 오류 설명은
 * 유지하며 포커스를 비밀 필드로 보내는 것</b>.
 *
 * <p>placeholder를 라벨로 쓰지 않는다. 값을 입력하는 순간 사라지므로 그것은 라벨이 아니고, 이
 * 폼에서는 무엇을 넣는 칸인지가 조작 내내 보여야 한다.
 *
 * <p><b>붙여넣기를 막지 않는다.</b> API Key와 Secret은 사람이 옮겨 적을 값이 아니다. 막는 폼이
 * 흔하므로 도움말이 "붙여 넣으세요"라고 직접 말한다.
 *
 * <p><b>브라우저에 아무것도 남기지 않는다.</b> 두 값은 React 상태로만 살고 제출과 함께 사라지며,
 * {@code localStorage}·{@code sessionStorage}·쿠키에 쓰지 않는다. 폼 자체도
 * {@code autoComplete="off"}라 브라우저 비밀번호 관리자가 자동으로 저장을 제안하지 않는다.
 *
 * <p>네이티브 제출을 하지 않는다. {@code onSubmit}에서 기본 동작을 막고 {@code fetch}로 보내므로
 * CSP의 {@code form-action 'none'}을 넓히지 않아도 되고, JS가 죽은 상태에서 Enter를 눌러도
 * 자격정보가 어디로도 나가지 않는다 — 비밀을 다루는 폼에서 이쪽이 엄격하게 낫다
 * ({@code deploy/nginx/bokrilog.conf}).
 */
export function BybitConnectionForm({
  confirmation,
  pending,
  onSubmit,
  onCancel,
  rejection,
}: BybitConnectionFormProps): ReactElement {
  const prefix = useId()
  const [apiKey, setApiKey] = useState('')
  const [apiSecret, setApiSecret] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Readonly<Partial<Record<FieldName, string>>>>({})
  /**
   * Secret이 방금 지워졌는가.
   *
   * 필드 오류와 갈라 두는 이유는 뜻이 다르기 때문이다. 필드 오류는 "입력이 잘못됐다"이고 이것은
   * "서버가 거절해서 서버로 보냈던 값을 지웠다"이며, 사용자가 해야 할 일도 다르다.
   */
  const [secretCleared, setSecretCleared] = useState(false)

  const secretRef = useRef<HTMLInputElement>(null)
  const summaryRef = useRef<HTMLDivElement>(null)
  const headingRef = useRef<HTMLHeadingElement>(null)

  /*
   * 폼이 열리면 그 제목으로 초점을 옮긴다(Story 2.17 리뷰). 여는 버튼은 폼과 함께 사라지므로 옮기지 않으면 초점이
   * `<body>`로 떨어진다. 사용자가 누른 조작의 결과이고 커밋과 함께 성립해야 하는 효과라 layout effect다.
   */
  useLayoutEffect(() => {
    headingRef.current?.focus()
  }, [])

  const apiKeyId = `${prefix}-api-key`
  const apiSecretId = `${prefix}-api-secret`
  const apiKeyHelpId = `${prefix}-api-key-help`
  const apiSecretHelpId = `${prefix}-api-secret-help`
  const apiKeyErrorId = `${prefix}-api-key-error`
  const apiSecretErrorId = `${prefix}-api-secret-error`
  const secretClearedId = `${prefix}-api-secret-cleared`
  const confirmationId = `${prefix}-confirmation`
  const confirmationHelpId = `${prefix}-confirmation-help`

  /** 요약에 실을 문장 전부. 필드 오류가 먼저이고 서버 거절이 뒤다. */
  const summary: readonly string[] = [
    ...(fieldErrors.apiKey === undefined ? [] : [fieldErrors.apiKey]),
    ...(fieldErrors.apiSecret === undefined ? [] : [fieldErrors.apiSecret]),
    ...(rejection === null ? [] : [rejection]),
  ]

  /** 그 필드의 오류만 걷는다. 다른 필드의 오류와 서버 거절 문장은 그대로 남는다. */
  function clearFieldError(field: FieldName): void {
    setFieldErrors((current) => {
      if (current[field] === undefined) {
        return current
      }
      // 키를 지우지 않고 `undefined`로 덮는다. 렌더가 보는 것은 `=== undefined` 하나이고,
      // 동적 키 삭제는 이 저장소의 lint가 막는다.
      return { ...current, [field]: undefined }
    })
  }

  function handleSubmit(event: SyntheticEvent<HTMLFormElement>): void {
    /*
     * 네이티브 제출을 막는다. 이 한 줄이 없으면 브라우저가 폼을 그대로 보내고, 그때 자격정보가
     * 쿼리 문자열이나 `form-action` 대상으로 나간다.
     */
    event.preventDefault()
    if (pending) {
      return
    }

    const trimmedKey = apiKey.trim()
    const errors: Partial<Record<FieldName, string>> = {}
    if (trimmedKey.length === 0) {
      errors.apiKey = connectionContent.form.apiKeyRequired
    }
    /*
     * Secret은 다듬지 않는다. 양 끝 공백이 값의 일부일 수 있고, 다듬어서 보내면 사용자가 붙여
     * 넣은 것과 다른 값이 검증된다 — 그 실패는 "키가 틀렸다"로만 보인다.
     */
    if (apiSecret.length === 0) {
      errors.apiSecret = connectionContent.form.apiSecretRequired
    }
    if (Object.keys(errors).length > 0) {
      setFieldErrors(errors)
      setSecretCleared(false)
      // 포커스는 언제나 비밀 필드로 보낸다. 두 필드가 함께 비어 있어도 그렇다.
      secretRef.current?.focus()
      return
    }

    setFieldErrors({})
    void onSubmit(trimmedKey, apiSecret).then((outcome) => {
      if (outcome === 'connected') {
        // 성공하면 폼이 사라진다. 그래도 값은 지운다 — 언마운트가 늦어도 메모리에 남지 않는다.
        setApiKey('')
        setApiSecret('')
        setSecretCleared(false)
        return
      }
      if (outcome === 'ignored') {
        // 요청이 나가지 않았다. 지울 이유가 없다.
        return
      }
      /*
       * 거절됐다. **Secret만** 지운다. API Key와 오류 설명은 그대로 두고 포커스를 비밀 필드로
       * 보낸다 — 사용자가 다시 할 일은 Secret을 붙여 넣는 것 하나다.
       */
      setApiSecret('')
      setSecretCleared(true)
      secretRef.current?.focus()
    })
  }

  return (
    <form
      aria-labelledby={`${prefix}-heading`}
      /*
       * 브라우저 비밀번호 관리자가 이 값을 저장하자고 제안하지 않게 한다. 자격정보를 브라우저
       * 저장소에 두지 않는다는 계약이 폼 속성에서도 성립해야 한다.
       */
      autoComplete="off"
      className={styles.form}
      noValidate
      onSubmit={handleSubmit}
    >
      <h3 className={styles.heading} id={`${prefix}-heading`} ref={headingRef} tabIndex={-1}>
        {connectionContent.form.heading}
      </h3>

      {/*
        오류 요약. 필드 오류와 서버 거절을 한 자리에 모은다.

        `role="alert"`이라 나타나는 순간 보조기술이 읽는다. 요약이 없으면 화면 아래쪽 필드 오류가
        스크린리더 사용자에게 도달하지 않는다.
      */}
      {summary.length === 0 ? null : (
        <div className={styles.errorSummary} ref={summaryRef} role="alert">
          <p className={styles.errorSummaryHeading}>{connectionContent.form.errorSummaryHeading}</p>
          <ul className={styles.errorList}>
            {summary.map((message) => (
              <li key={message}>{message}</li>
            ))}
          </ul>
        </div>
      )}

      {/*
        확인 문구. 서버가 요청마다 새로 만들고 화면은 그대로 렌더한다.

        입력 칸이 아니다. 사용자가 옮겨 적는 값이 아니라 **본 값**이며, 제출이 같은 문자열을
        되보내 허가의 다섯 번째 귀속 축을 만든다(`BybitConnectionFlow`).
      */}
      <div className={styles.field}>
        <p className={styles.label} id={confirmationId}>
          {connectionContent.form.confirmationLabel}
        </p>
        <p aria-labelledby={confirmationId} className={styles.confirmation}>
          {confirmation}
        </p>
        <p className={styles.help} id={confirmationHelpId}>
          {connectionContent.form.confirmationHelp}
        </p>
      </div>

      <div className={styles.field}>
        {/* 영구 라벨이다. placeholder로 대신하지 않는다. */}
        <label className={styles.label} htmlFor={apiKeyId}>
          {connectionContent.form.apiKeyLabel}
        </label>
        <input
          aria-describedby={
            fieldErrors.apiKey === undefined ? apiKeyHelpId : `${apiKeyHelpId} ${apiKeyErrorId}`
          }
          aria-invalid={fieldErrors.apiKey !== undefined}
          autoComplete="off"
          className={styles.input}
          id={apiKeyId}
          name="apiKey"
          onChange={(event) => {
            setApiKey(event.target.value)
            // 고친 필드의 오류는 그 자리에서 걷는다. 남겨 두면 `aria-invalid`가 이미 고친 값을
            // 계속 잘못됐다고 말한다.
            clearFieldError('apiKey')
          }}
          spellCheck={false}
          type="text"
          value={apiKey}
        />
        <p className={styles.help} id={apiKeyHelpId}>
          {connectionContent.form.apiKeyHelp}
        </p>
        {fieldErrors.apiKey === undefined ? null : (
          <p className={styles.fieldError} id={apiKeyErrorId}>
            {fieldErrors.apiKey}
          </p>
        )}
      </div>

      <div className={styles.field}>
        <label className={styles.label} htmlFor={apiSecretId}>
          {connectionContent.form.apiSecretLabel}
        </label>
        {/*
          `type="password"`다. 어깨너머로 읽히는 경로를 닫는다.

          `autoComplete="off"`가 함께 있어 브라우저가 저장을 제안하지 않는다. 붙여넣기는 막지
          않는다 — `onPaste`를 다루는 코드가 여기 없는 것이 그 계약이다.
        */}
        <input
          aria-describedby={describedBy(
            apiSecretHelpId,
            fieldErrors.apiSecret === undefined ? null : apiSecretErrorId,
            secretCleared ? secretClearedId : null,
          )}
          aria-invalid={fieldErrors.apiSecret !== undefined}
          autoComplete="off"
          className={styles.input}
          id={apiSecretId}
          name="apiSecret"
          onChange={(event) => {
            setApiSecret(event.target.value)
            clearFieldError('apiSecret')
            // 다시 입력하기 시작했다. "지웠습니다" 안내는 더 이상 지금 상태를 말하지 않는다.
            setSecretCleared(false)
          }}
          ref={secretRef}
          spellCheck={false}
          type="password"
          value={apiSecret}
        />
        <p className={styles.help} id={apiSecretHelpId}>
          {connectionContent.form.apiSecretHelp}
        </p>
        {fieldErrors.apiSecret === undefined ? null : (
          <p className={styles.fieldError} id={apiSecretErrorId}>
            {fieldErrors.apiSecret}
          </p>
        )}
        {secretCleared ? (
          <p className={styles.help} id={secretClearedId}>
            {connectionContent.form.apiSecretCleared}
          </p>
        ) : null}
      </div>

      <div className={styles.actions}>
        {/*
          `aria-disabled`이지 `disabled`가 아니다. 그 속성은 요소를 탭 순서에서 떨어뜨려 방금
          `Enter`로 제출한 키보드·스크린리더 사용자가 그 순간 포커스를 잃는다. 실제 중복 제출
          차단은 `useBybitConnection`의 ref 가드가 한다.
        */}
        <button aria-disabled={pending} className={styles.submit} type="submit">
          {connectionContent.form.submit}
        </button>
        <button
          aria-disabled={pending}
          className={styles.cancel}
          onClick={() => {
            if (pending) {
              return
            }
            onCancel()
          }}
          type="button"
        >
          {connectionContent.form.cancel}
        </button>
      </div>
    </form>
  )
}

/** 있는 id만 이어 붙인다. 빈 문자열을 넘기면 존재하지 않는 요소를 가리키는 참조가 생긴다. */
function describedBy(...ids: readonly (string | null)[]): string {
  return ids.filter((id): id is string => id !== null).join(' ')
}
