/**
 * What an effect has done at the first moment its commit can be seen.
 *
 * Deliberate first-sight guards (`spec-frontend-effect-timing.md`): they pin an effect to the commit that
 * changes the screen — a layout effect — without CPU load. A `waitFor` would wait for the effect and pass
 * either way; these record the result at the first sight of the new content instead.
 *
 * How it separates the two kinds. `release` lets an answer through (a deferred `fetch` resolving), so the
 * update it causes is a default-priority update from outside any event, as in the app. React renders and
 * commits it in one scheduler task and runs its layout effects inside the commit. The commit asks the
 * scheduler to yield for paint, so the commit's passive effects run in a later task. A `MutationObserver`
 * callback is a microtask right after the commit's task: it sees the new DOM after the layout effects and
 * before the passive ones. `record` runs there, synchronously.
 *
 * The act environment is off while this waits — the update is meant to happen outside act, and with the
 * environment on React would warn about it — and stays off for a few more tasks so the commit's passive
 * effects (and what they set) run the same way. The precedent is `CalculationEvidence.test.tsx`'s late-answer
 * test.
 *
 * @param appeared whether the new content is in the document; it must be false before `release`
 * @param record what to keep at the first sight, read synchronously in the observer's callback
 * @param release lets the answer through
 */
export async function atFirstSight<T>(
  appeared: () => boolean,
  record: () => T,
  release: () => void,
): Promise<T> {
  if (appeared())
    throw new Error('The content is already there: nothing to see for the first time.')
  const environment = globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }
  const previous = environment.IS_REACT_ACT_ENVIRONMENT
  environment.IS_REACT_ACT_ENVIRONMENT = false
  try {
    const seen = await new Promise<T>((resolve, reject) => {
      const observer = new MutationObserver(() => {
        if (!appeared()) return
        observer.disconnect()
        clearTimeout(limit)
        resolve(record())
      })
      const limit = setTimeout(() => {
        observer.disconnect()
        reject(new Error('The content never appeared.'))
      }, 2_000)
      observer.observe(document.body, {
        attributes: true,
        characterData: true,
        childList: true,
        subtree: true,
      })
      release()
    })
    // The commit's passive effects, and any update they make, run outside act.
    for (let tick = 0; tick < 5; tick += 1) await new Promise((settle) => setTimeout(settle, 10))
    return seen
  } finally {
    environment.IS_REACT_ACT_ENVIRONMENT = previous
  }
}

/** A promise and the function that settles it, for an answer a first-sight guard releases. */
export function deferred<T>(): {
  readonly promise: Promise<T>
  readonly resolve: (value: T) => void
} {
  let resolve: (value: T) => void = () => undefined
  const promise = new Promise<T>((settle) => {
    resolve = settle
  })
  return { promise, resolve }
}
