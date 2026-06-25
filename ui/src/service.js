/*
 * Service layer for plugin "build-jenkins".
 *
 * Tool-level plugin (lives at `service:build:jenkins`). The parent
 * `plugin-build` delegates the subscription-row hooks to us. Ports the legacy
 * `jenkins.js` delegated rendering onto the new VNode delegation:
 *
 *   - renderFeatures        → a link to the Jenkins project home (url + /job/<job>)
 *     plus a BUILD action that triggers the job via the ligoj REST API and then
 *     polls this subscription while it runs (legacy `serviceBuildJenkinsBuild`).
 *   - renderDetailsKey      → the job DISPLAY NAME (subscription.data.job.name,
 *     falling back to the configured job path) with the job DESCRIPTION as an
 *     implicit v-tooltip when available.
 *   - renderDetailsFeatures → the live job STATUS icon (colour + building spinner)
 *     with the translated status (+ "building") as a v-tooltip, and a link to the
 *     last job EXECUTION (build #N) when known.
 *
 * Tooltips are implicit: any `title:` on a returned VNode is promoted to a themed
 * v-tooltip by the host (PluginFeatures). The live `job` fields come from
 * `subscription.data.job` (populated by the status/refresh round-trip); the
 * details degrade cleanly to the key chip alone until that arrives.
 *
 * Kept free of Vue SFC imports so it can be unit-tested.
 */
import { h, reactive } from 'vue'
import { VBtn, VIcon, renderServiceLink, renderDetailsChip, useApi, useI18nStore } from '@ligoj/host'

const PARAM_URL = 'service:build:jenkins:url'
const PARAM_JOB = 'service:build:jenkins:job'

// Transient per-subscription build state, REACTIVE so the row re-renders as the
// trigger/poll progresses. The subscription objects handed to the renderer are
// plain (built inside a computed in the host views), so we keep the freshly
// polled `data` overlay HERE rather than mutating them — reading buildState[id]
// during render tracks it, and replacing the entry triggers a re-render.
const buildState = reactive({}) // id -> { triggering, polling, data }
const pollers = {} // id -> interval handle (non-reactive bookkeeping)

const POLL_INTERVAL = 3000 // ms between status refreshes while building
const POLL_GRACE = 30000 // ms to keep polling for a queued build to actually start
const POLL_MAX = 5 * 60 * 1000 // ms hard cap on a single poll session

// Jenkins job colour → mdi icon + Vuetify colour (legacy jobStatusColor/Typo).
const STATUS_META = {
  blue: { icon: 'mdi-check-circle', color: 'success' },
  red: { icon: 'mdi-close-circle', color: 'error' },
  yellow: { icon: 'mdi-alert-circle', color: 'warning' },
  disabled: { icon: 'mdi-cancel', color: 'grey' },
}
const STATUS_DEFAULT = { icon: 'mdi-help-circle', color: 'grey' }

// Encode a Jenkins job path segment-by-segment for a URL (mirrors the legacy).
function encodeJob(job) {
  return String(job || '').split('/').map(encodeURIComponent).join('/')
}
function jobHomeUrl(params) {
  const url = String(params?.[PARAM_URL] || '').replace(/\/$/, '')
  return `${url}/job/${encodeJob(params?.[PARAM_JOB])}`
}

// vue-i18n echoes the key back when missing; treat that as "no translation".
function msg(t, key, fallback) {
  const v = t(key)
  return v === key ? fallback : v
}

// Live job data: the freshest polled overlay (if any) wins over the row snapshot.
function liveJob(subscription) {
  const id = subscription?.id
  const overlaid = id != null ? buildState[id]?.data?.job : null
  return overlaid || subscription?.data?.job
}

function isBusy(id) {
  const st = id != null ? buildState[id] : null
  return !!(st && (st.triggering || st.polling))
}

// Stop the poll loop for a subscription and clear its "polling" flag.
function stopPolling(id) {
  if (pollers[id]) {
    clearInterval(pollers[id])
    delete pollers[id]
  }
  if (buildState[id]) buildState[id] = { ...buildState[id], polling: false }
}

// Refresh this subscription on a timer while its Jenkins job runs, overlaying
// the returned `data` (carrying `job.building`/`job.status`) so the row's status
// icon and the build button reflect progress live. Stops once the build is no
// longer running (it finished, or never started within the grace window) or
// after a hard time cap, whichever comes first.
function startPolling(subscription, api) {
  const id = subscription?.id
  if (id == null || pollers[id]) return // at most one poller per subscription
  buildState[id] = { ...(buildState[id] || {}), polling: true }
  let elapsed = 0
  let sawBuilding = false

  const tick = async () => {
    try {
      const resp = await api.get(`rest/subscription/status/${encodeURIComponent(id)}/refresh`, { silent: true, raw: true })
      if (resp.ok) {
        const d = await resp.json()
        if (d && typeof d === 'object') buildState[id] = { ...(buildState[id] || {}), data: d.data }
      }
    } catch { /* transient error: keep polling */ }
    const building = !!buildState[id]?.data?.job?.building
    if (building) sawBuilding = true
    elapsed += POLL_INTERVAL
    if (elapsed >= POLL_MAX || (!building && (sawBuilding || elapsed >= POLL_GRACE))) stopPolling(id)
  }

  pollers[id] = setInterval(tick, POLL_INTERVAL)
  tick() // refresh immediately so the building state shows without a delay
}

// Trigger the Jenkins job through the ligoj REST API, then poll while it runs.
// Mirrors the legacy serviceBuildJenkinsBuild (POST .../build/{subscription}).
async function triggerBuild(subscription) {
  const id = subscription?.id
  if (id == null || isBusy(id)) return
  const api = useApi()
  buildState[id] = { ...(buildState[id] || {}), triggering: true }
  try {
    await api.post(`rest/service/build/jenkins/build/${encodeURIComponent(id)}`)
    startPolling(subscription, api)
  } finally {
    if (buildState[id]) buildState[id] = { ...buildState[id], triggering: false }
  }
}

// Build action button: triggers the job, spins (and disables itself) while the
// trigger/poll is in flight. `title:` is promoted to a v-tooltip by the host.
function buildButton(subscription, t) {
  const id = subscription?.id
  if (id == null) return null
  const busy = isBusy(id)
  return h(VBtn, {
    icon: true,
    size: 'small',
    variant: 'text',
    color: busy ? 'primary' : undefined,
    disabled: busy,
    title: busy ? t('service:build:jenkins:building') : t('service:build:jenkins:build'),
    onClick: () => triggerBuild(subscription),
  }, () => h(VIcon, { size: 'small', class: busy ? 'mdi-spin' : undefined }, () => (busy ? 'mdi-loading' : 'mdi-play')))
}

/** Link to the Jenkins project home (url + /job/<job>) + a build action. */
function renderFeatures(subscription) {
  const params = subscription?.parameters
  if (!params?.[PARAM_URL] || !params?.[PARAM_JOB]) return []
  const { t } = useI18nStore()
  const out = [renderServiceLink({ icon: 'mdi-jenkins', href: jobHomeUrl(params), title: t('service:build:jenkins:job') })]
  const build = buildButton(subscription, t)
  if (build) out.push(build)
  return out
}

/** Job DISPLAY NAME chip; the job description is the (implicit) tooltip. */
function renderDetailsKey(subscription) {
  const jobParam = subscription?.parameters?.[PARAM_JOB]
  if (!jobParam) return null
  const { t } = useI18nStore()
  const job = liveJob(subscription)
  return renderDetailsChip({
    icon: 'mdi-jenkins',
    text: job?.name || jobParam,
    title: job?.description || t('service:build:jenkins:job'),
  })
}

// The job status icon (colour + building spinner) with a status tooltip.
function statusIcon(job, t) {
  const status = String(job?.status || '').toLowerCase()
  const meta = STATUS_META[status] || STATUS_DEFAULT
  const label = msg(t, `service:build:jenkins:status-${status}`, status || '?')
  const title = job?.building ? `${label} (${t('service:build:jenkins:building')})` : label
  return h(VIcon, {
    size: 'small',
    color: meta.color,
    title,
    // MDI webfont spin while building (mirrors the legacy fa-spin).
    class: job?.building ? 'mdi-spin' : undefined,
  }, () => (job?.building ? 'mdi-sync' : meta.icon))
}

/** Live status icon + a link to the last job execution (build #N). */
function renderDetailsFeatures(subscription) {
  const job = liveJob(subscription)
  if (!job) return null
  const { t } = useI18nStore()
  const out = [statusIcon(job, t)]
  if (job.lastBuild != null) {
    out.push(renderServiceLink({
      icon: 'mdi-play-circle-outline',
      href: `${jobHomeUrl(subscription.parameters)}/${job.lastBuild}/`,
      title: `${t('service:build:jenkins:last-build')} #${job.lastBuild}`,
    }))
  }
  return out
}

export default { renderFeatures, renderDetailsKey, renderDetailsFeatures }
