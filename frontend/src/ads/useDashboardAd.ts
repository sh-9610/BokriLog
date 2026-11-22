import { useEffect, useState } from 'react'

import { DASHBOARD_AD_CONFIG_PATH } from '../content/planSync'
import { objectOf, readJson } from '../session/http'
import { SESSION_REQUEST_TIMEOUT_MS } from '../session/useSessionState'
import { recordAdEvent } from '../telemetry/beacon'
import { showInterstitial } from './gptAdapter'

const AD_UNIT_PATH = /^\/[0-9]+(\/[A-Za-z0-9_.-]+)+$/
const AD_CONFIG_MAX_AGE_MS = 120_000

/** 광고 설정을 읽고 활성인 대시보드에서만 슬롯을 준비한다. 반환값은 설정의 만료 시각이다. */
export function useDashboardAd(enabled: boolean, csrfToken: string | null): number | null {
  const [lease, setLease] = useState<{
    token: string
    expiresAt: number
    lifetime: AbortSignal
  } | null>(null)

  useEffect(() => {
    if (!enabled || csrfToken === null || window.location.search || window.location.hash) return
    const token = csrfToken
    let controller: AbortController | undefined
    let recheckTimer: number | undefined
    let cancelAd: (() => void) | undefined
    let currentPath: string | undefined
    let validUntil = 0
    let ready = false
    const lifetime = new AbortController()
    const stopped = () => lifetime.signal.aborted

    function stop(): void {
      if (stopped()) return
      lifetime.abort()
      controller?.abort()
      window.clearTimeout(recheckTimer)
      cancelAd?.()
    }

    function disable(): void {
      stop()
      setLease(null)
    }

    async function check(): Promise<void> {
      if (stopped() || controller !== undefined) return
      window.clearTimeout(recheckTimer)
      const request = new AbortController()
      controller = request
      const deadline = window.setTimeout(() => {
        request.abort()
      }, SESSION_REQUEST_TIMEOUT_MS)
      try {
        const response = await fetch(DASHBOARD_AD_CONFIG_PATH, {
          credentials: 'same-origin',
          cache: 'no-store',
          headers: { Accept: 'application/json' },
          signal: request.signal,
        })
        const payload = response.status === 200 ? await readJson(response) : null
        if (stopped()) return
        const path = objectOf(payload)?.adUnitPath
        if (
          request.signal.aborted ||
          typeof path !== 'string' ||
          !AD_UNIT_PATH.test(path) ||
          (currentPath !== undefined && currentPath !== path)
        ) {
          disable()
          return
        }
        validUntil = performance.now() + AD_CONFIG_MAX_AGE_MS
        recheckTimer = window.setTimeout(() => {
          setLease(null)
          if (document.visibilityState !== 'hidden') void check()
        }, AD_CONFIG_MAX_AGE_MS)
        if (currentPath !== undefined) {
          if (ready) setLease({ token, expiresAt: validUntil, lifetime: lifetime.signal })
          return
        }
        currentPath = path
        recordAdEvent('REQUESTED')
        cancelAd = showInterstitial(
          path,
          (reason) => {
            recordAdEvent('FAILED', reason)
            disable()
          },
          () => {
            if (stopped()) return
            ready = true
            recordAdEvent('READY')
            setLease({ token, expiresAt: validUntil, lifetime: lifetime.signal })
          },
        )
      } catch {
        if (!stopped()) disable()
      } finally {
        controller = undefined
        window.clearTimeout(deadline)
      }
    }

    const onVisibility = (): void => {
      if (document.visibilityState === 'visible' && performance.now() >= validUntil) void check()
    }

    window.addEventListener('pagehide', disable)
    document.addEventListener('visibilitychange', onVisibility)
    if (document.visibilityState !== 'hidden') void check()
    return () => {
      window.removeEventListener('pagehide', disable)
      document.removeEventListener('visibilitychange', onVisibility)
      stop()
    }
  }, [enabled, csrfToken])

  return enabled && lease !== null && lease.token === csrfToken && !lease.lifetime.aborted
    ? lease.expiresAt
    : null
}
