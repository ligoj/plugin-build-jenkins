/*
 * Service layer for plugin "build-jenkins".
 *
 * Tool-level plugin (lives at `service:build:jenkins`). The parent
 * `plugin-build` delegates the subscription-row hooks to us. Ports the legacy
 * `jenkins.js` delegated rendering onto the new VNode delegation:
 *
 *   - renderFeatures        → a link to the Jenkins project home (url + /job/<job>),
 *     a BUILD action that triggers the job via the ligoj REST API and then polls
 *     this subscription while it runs (legacy `serviceBuildJenkinsBuild`), and an
 *     optional HELP link (legacy `renderServiceHelpLink`, `service:build:help`).
 *   - renderDetailsKey      → the job DISPLAY NAME (subscription.data.job.name,
 *     falling back to the configured job path) with the job DESCRIPTION as an
 *     implicit v-tooltip when available.
 *   - renderDetailsFeatures → the live job STATUS icon (colour + building spinner)
 *     with the translated status (+ "building") as a v-tooltip, and a link to the
 *     last job EXECUTION (build #N) when known. In MULTI-BRANCH / pull-request
 *     mode (`job.jobs`), one link + status icon per branch instead (legacy).
 *   - parameterField        → custom subscribe-wizard inputs for the job /
 *     template-job parameters (legacy `configureSubscriptionParameters`).
 *
 * Tooltips are implicit: any `title:` on a returned VNode is promoted to a themed
 * v-tooltip by the host (PluginFeatures). The live `job` fields come from
 * `subscription.data.job` (populated by the status/refresh round-trip); the
 * details degrade cleanly to the key chip alone until that arrives.
 */
import { h, reactive } from 'vue'
import { VBtn, VIcon, renderServiceLink, renderDetailsChip, useApi, useI18nStore } from '@ligoj/host'
import JenkinsJobField from './fields/JenkinsJobField.vue'
import JenkinsTemplateJobField from './fields/JenkinsTemplateJobField.vue'

const PARAM_URL = 'service:build:jenkins:url'
const PARAM_JOB = 'service:build:jenkins:job'
const PARAM_TEMPLATE_JOB = 'service:build:jenkins:template-job'
const PARAM_HELP = 'service:build:help'

// Parameters the Jenkins tool owns a custom subscribe-wizard input for.
const PARAMETER_FIELDS = {
  [PARAM_JOB]: JenkinsJobField,
  [PARAM_TEMPLATE_JOB]: JenkinsTemplateJobField,
}

// Transient per-subscription build state, REACTIVE so the row re-renders as the
// trigger/poll progresses. The subscription objects handed to the renderer are
// plain (built inside a computed in the host views), so we keep the freshly
// polled `data` overlay HERE rather than mutating them — reading buildState[id]
// during render tracks it, and replacing the entry triggers a re-render.
const buildState = reactive({}) // id -> { triggering, polling, data }
const pollers = {} // id -> interval handle (non-reactive bookkeeping)

const POLL_INTERVAL = 3000 // ms between status refreshes while building
const POLL_GRACE = 12000 // ms to wait for a queued build to actually start
const POLL_MAX = 2 * 60 * 1000 // ms hard cap on a single poll session

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

// Encode a NESTED job id for a folder URL: each path segment becomes its own
// `/job/<segment>` so the URL traverses Jenkins folders (legacy multi-branch).
function jobFolderUrl(params, jobId) {
  const url = String(params?.[PARAM_URL] || '').replace(/\/$/, '')
  const nested = String(jobId || '').split('/').map(encodeURIComponent).join('/job/')
  return `${url}/job/${nested}`
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
// icon and the build button reflect progress live.
//
// Termination — the previous version only ever evaluated the overlay (which a
// transient refresh failure could leave reading "building" forever). This reads
// the FRESH response each tick and stops the moment a successful refresh reports
// the job is no longer building:
//   - finished:  not building AND (we saw it building, or the build timestamp
//                advanced past the one captured at trigger time);
//   - never ran: nothing started within the grace window;
//   - failsafe:  refreshes keep failing past the grace window, or the hard cap.
function startPolling(subscription, api) {
  const id = subscription?.id
  if (id == null || pollers[id]) return // at most one poller per subscription
  buildState[id] = { ...(buildState[id] || {}), polling: true }
  // `lastBuild` is the last build's TIMESTAMP (Jenkins `lastBuild[timestamp]`):
  // a reliable completion marker — it advances once the triggered build ends,
  // even if the backend is slow to clear the transient `building` flag.
  const startMarker = liveJob(subscription)?.lastBuild ?? null
  let elapsed = 0
  let sawBuilding = false

  const tick = async () => {
    let job
    let refreshed = false
    try {
      const resp = await api.get(`rest/subscription/status/${encodeURIComponent(id)}/refresh`, { silent: true, raw: true })
      if (resp.ok) {
        const d = await resp.json()
        if (d && typeof d === 'object') {
          buildState[id] = { ...(buildState[id] || {}), data: d.data }
          job = d.data?.job
          refreshed = true
        }
      }
    } catch { /* transient error: handled by the grace/cap failsafes below */ }
    elapsed += POLL_INTERVAL

    if (refreshed) {
      const building = !!job?.building
      if (building) sawBuilding = true
      const finished = startMarker != null && job?.lastBuild != null && job.lastBuild !== startMarker
      // Not building any more → the build is done (it ran, a new build landed,
      // or it never started within the grace window). Stop.
      if (!building && (sawBuilding || finished || elapsed >= POLL_GRACE)) return stopPolling(id)
    } else if (elapsed >= POLL_GRACE) {
      // Refreshes are failing — don't poll a dead endpoint to the hard cap.
      return stopPolling(id)
    }
    if (elapsed >= POLL_MAX) stopPolling(id)
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

/** Link to the Jenkins project home (url + /job/<job>), a build action and an
 *  optional help link (legacy `renderServiceHelpLink`, `service:build:help`). */
function renderFeatures(subscription) {
  const params = subscription?.parameters
  if (!params?.[PARAM_URL] || !params?.[PARAM_JOB]) return []
  const { t } = useI18nStore()
  // The job DESCRIPTION (retrieved from Jenkins, when available) makes the
  // tooltip; falls back to the generic "Job" label otherwise.
  const title = liveJob(subscription)?.description || t('service:build:jenkins:job')
  // mdi has no `jenkins` glyph (removed for trademark), so the legacy generic
  // "home" icon is used for the project link — same as the other tool plugins.
  const out = [renderServiceLink({ icon: 'mdi-home', href: jobHomeUrl(params), title })]
  const build = buildButton(subscription, t)
  if (build) out.push(build)
  // Optional documentation link — only rendered when the subscription carries
  // a help URL parameter (the legacy `renderServiceHelpLink` is conditional).
  if (params[PARAM_HELP]) {
    out.push(renderServiceLink({ icon: 'mdi-help-circle-outline', href: params[PARAM_HELP], title: msg(t, PARAM_HELP, 'Help') }))
  }
  return out
}

/** Job DISPLAY NAME chip; the job description is the (implicit) tooltip. */
function renderDetailsKey(subscription) {
  const jobParam = subscription?.parameters?.[PARAM_JOB]
  if (!jobParam) return null
  const { t } = useI18nStore()
  const job = liveJob(subscription)
  return renderDetailsChip({
    icon: 'mdi-cog',
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

// Multi-branch / pull-request mode: one link + status icon per branch. Each
// branch (`job.jobs[i]`) is itself a Job carrying its OWN status — unlike the
// legacy, which rendered the parent job's status against every branch row.
function renderBranches(subscription, job, t) {
  const params = subscription?.parameters
  const baseUrl = jobFolderUrl(params, job.id)
  return job.jobs.map((b) => {
    const isPr = !!b.pullRequestBranch
    const leaf = b.name || b.id
    const altName = (typeof b.name === 'string' && b.name !== b.id) ? ` (${b.name})` : ''
    const kind = isPr ? t('service:build:jenkins:pull-request') : t('service:build:jenkins:branch')
    // Pull requests live under Jenkins' change-requests view; branches under the
    // job's own folder (legacy additionalPath).
    const path = isPr
      ? `/view/change-requests/job/${encodeURIComponent(leaf)}/`
      : `/job/${encodeURIComponent(leaf)}/`
    return h('span', { key: b.id, class: 'mr-2 d-inline-flex align-center' }, [
      renderServiceLink({
        icon: isPr ? 'mdi-source-pull' : 'mdi-source-branch',
        href: `${baseUrl}${path}`,
        title: `${kind} — ${b.id}${altName}`,
      }),
      statusIcon(b, t),
    ])
  })
}

/** Live job status icon (legacy single-job mode), or — in multi-branch /
 *  pull-request mode — one link + status icon per branch. */
function renderDetailsFeatures(subscription) {
  const job = liveJob(subscription)
  if (!job) return null
  const { t } = useI18nStore()
  if (job.jobs?.length) return renderBranches(subscription, job, t)
  // Single-job mode: just the live status icon, mirroring the legacy. (The
  // backend's `lastBuild` is a build TIMESTAMP, not a number, so it can't be
  // turned into a `/job/<job>/<n>/` execution URL.)
  return [statusIcon(job, t)]
}

/**
 * Subscribe-wizard hook: replace the default parameter input for the Jenkins
 * `job` / `template-job` parameters with the custom autocomplete / validated
 * inputs (legacy `configureSubscriptionParameters`). Returns null for every
 * other parameter (default type-based rendering) and when editing a node
 * directly — these fields drive subscription creation, not node config.
 */
function parameterField({ parameter, isNode } = {}) {
  if (isNode) return null
  return PARAMETER_FIELDS[parameter?.id] || null
}

export default { renderFeatures, renderDetailsKey, renderDetailsFeatures, parameterField }
