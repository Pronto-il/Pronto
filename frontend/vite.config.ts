import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * Development hosts that can only mean "this is somebody's laptop". Mirrors the backend's
 * `auth.config.CorsOriginStartupGuard`, deliberately: a production frontend pointed at localhost
 * and a production backend allowing localhost are the same deployment mistake seen from two sides.
 */
const DEVELOPMENT_HOSTS = ['localhost', '127.0.0.1', '0.0.0.0', '[::1]']

/**
 * Production MS4 — refuse to *build* a production bundle that has no API origin.
 *
 * Vite statically inlines `import.meta.env.VITE_API_BASE_URL` at build time, so an unset variable
 * is not a runtime misconfiguration that can be corrected by an operator: it is compiled into the
 * artifact. Before this check, `shared/api/httpClient.ts` fell back to `http://localhost:8080`,
 * which meant a production deployment that forgot the variable shipped a bundle calling localhost
 * from every user's browser — and `shared/realtime/stompClient.ts` derives the WebSocket origin
 * from the same value, so realtime broke identically and for the same invisible reason.
 *
 * The backend's guards refuse to *start* on unsafe configuration; this is the frontend equivalent,
 * and it has to fire at build time because that is the only moment the value still exists.
 */
function assertProductionApiBaseUrl(apiBaseUrl: string | undefined) {
  const fail = (reason: string) => {
    throw new Error(
      `Refusing to build a production bundle: VITE_API_BASE_URL ${reason}\n` +
        `  Vite inlines this value at build time, so it cannot be corrected after the fact — an\n` +
        `  unset variable ships a bundle that calls the wrong origin from every user's browser.\n` +
        `  Set VITE_API_BASE_URL to the backend's public origin, e.g. https://api.example.com`,
    )
  }

  const value = apiBaseUrl?.trim()
  if (!value) {
    fail('is not set.')
    return
  }

  // Parsed on its own, with nothing else inside the try: a `fail()` thrown from within this block
  // would be caught by the very `catch` that is meant to report a malformed URL, and every failure
  // would be reported as "not a valid absolute URL" regardless of what was actually wrong.
  let url: URL
  try {
    url = new URL(value)
  } catch {
    fail(`is '${value}', which is not a valid absolute URL (expected e.g. https://api.example.com).`)
    return
  }

  if (url.protocol !== 'https:') {
    fail(`is '${value}', which is not HTTPS. Every JWT this app holds travels over that origin.`)
  }

  const host = url.hostname.toLowerCase()
  if (DEVELOPMENT_HOSTS.includes(host) || DEVELOPMENT_HOSTS.includes(`[${host}]`)) {
    fail(`is '${value}', a development origin.`)
  }
}

// https://vite.dev/config/
export default defineConfig(({ command, mode }) => {
  if (command === 'build' && mode === 'production') {
    // loadEnv, not process.env: it also reads .env/.env.production files, which is where a
    // deployment is just as likely to put the value as in the shell.
    const env = loadEnv(mode, process.cwd(), 'VITE_')
    assertProductionApiBaseUrl(env.VITE_API_BASE_URL)
  }

  return {
    plugins: [react()],
  }
})
