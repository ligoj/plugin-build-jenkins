/*
 * Service layer for plugin "build-jenkins".
 *
 * Tool-level plugin (lives at `service:build:jenkins`). The parent
 * `plugin-build` delegates the subscription-row hooks to us via its
 * `subPluginIdFor` delegation. Mirrors the legacy `jenkins.js`:
 *
 *   - renderFeatures   → a link to the Jenkins job (url + /job/<job>).
 *   - renderDetailsKey → the job-name chip.
 *
 * Both rows are built with the host's shared `renderServiceLink` /
 * `renderDetailsChip` helpers — one source of truth for the button/chip
 * shape, with the tooltip promoted implicitly by the host (no VTooltip
 * import). Kept free of Vue SFC imports so it can be unit-tested.
 */
import { renderServiceLink, renderDetailsChip, useI18nStore } from '@ligoj/host'

const PARAM_URL = 'service:build:jenkins:url'
const PARAM_JOB = 'service:build:jenkins:job'

/** Link to the Jenkins job. Mirrors the legacy
 *  `renderServicelink('home', url + '/job/' + job)`. */
function renderFeatures(subscription) {
  const params = subscription?.parameters
  const url = params?.[PARAM_URL]
  const job = params?.[PARAM_JOB]
  if (!url || !job) return []
  const { t } = useI18nStore()
  return [renderServiceLink({ icon: 'mdi-jenkins', href: `${url}/job/${job}`, title: t('service:build:jenkins:url') })]
}

/** Job-name chip. Mirrors the legacy `renderKey('service:build:jenkins:job')`. */
function renderDetailsKey(subscription) {
  const job = subscription?.parameters?.[PARAM_JOB]
  if (!job) return null
  const { t } = useI18nStore()
  return renderDetailsChip({ icon: 'mdi-jenkins', text: job, title: t('service:build:jenkins:job') })
}

export default { renderFeatures, renderDetailsKey }
