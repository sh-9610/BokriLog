/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 백엔드·프록시 설정은 Story 1.2 범위다. 이 설정은 정적 SPA만 다룬다.
export default defineConfig({
  plugins: [react()],
  server: {
    // DEVELOPMENT.md가 dev 서버 주소를 5173으로 못박는다. 조용히 다른 포트로 옮겨가지 않게 한다.
    port: 5173,
    strictPort: true,
  },
  build: {
    target: 'es2022',
    // 4KB 미만 asset은 번들에 `data:` URI로 인라인되고, 그 이상은 `dist/assets/`의 동일 출처 파일이 된다.
    // 어느 쪽이든 외부 CDN·폰트를 참조하지 않는다.
    assetsInlineLimit: 4096,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    // `playwright.config.test.ts`는 저장소 루트의 `playwright.config.ts`를, `lint-policy.test.ts`는
    // `eslint.config.ts`·`stylelint.config.js`를 검사한다. 대상이 `src/` 밖이라 시험도 그 옆에 두고
    // 여기서 두 파일만 명시적으로 더 받는다.
    include: ['src/**/*.test.{ts,tsx}', 'playwright.config.test.ts', 'lint-policy.test.ts'],
    exclude: ['e2e/**', 'node_modules/**', 'dist/**'],
    restoreMocks: true,
  },
})
