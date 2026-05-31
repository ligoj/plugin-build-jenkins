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
 * Kept free of Vue SFC imports so it can be unit-tested without a DOM.
 */
import { h } from 'vue'
import { VBtn, VChip, VIcon, useI18nStore } from '@ligoj/host'

const PARAM_URL = 'service:build:jenkins:url'
const PARAM_JOB = 'service:build:jenkins:job'

/**
 * Link to the Jenkins job. Mirrors the legacy
 * `renderServicelink('home', url + '/job/' + job)`.
 */
function renderFeatures(subscription) {
  const params = subscription?.parameters
  const url = params?.[PARAM_URL]
  const job = params?.[PARAM_JOB]
  if (!url || !job) return []
  const { t } = useI18nStore()
  return [
    h(
      VBtn,
      {
        icon: true,
        size: 'small',
        variant: 'text',
        href: `${url}/job/${job}`,
        target: '_blank',
        rel: 'noopener noreferrer',
        title: t('service:build:jenkins:url'),
      },
      () => h(VIcon, { size: 'small' }, () => 'mdi-jenkins'),
    ),
  ]
}

/**
 * Job-name chip for the subscription details column. Mirrors the legacy
 * `renderKey('service:build:jenkins:job')`.
 */
function renderDetailsKey(subscription) {
  const job = subscription?.parameters?.[PARAM_JOB]
  if (!job) return null
  const { t } = useI18nStore()
  return h(
    VChip,
    { size: 'small', variant: 'tonal', class: 'mr-1', title: t('service:build:jenkins:job') },
    () => [h(VIcon, { start: true, size: 'small' }, () => 'mdi-jenkins'), ' ', String(job)],
  )
}

export default { renderFeatures, renderDetailsKey }
