/*
 * Plugin "build-jenkins" — Jenkins implementation of plugin-build.
 *
 * Tool-level plugin: lives at `service:build:jenkins` in the node tree.
 * It augments the parent `plugin-build` via:
 *   - i18n: Jenkins parameter labels (url, user, api-token, job, …) for
 *     the subscribe wizard's auto-rendered parameter form.
 *   - feature('renderFeatures', subscription): the Jenkins job link.
 *   - feature('renderDetailsKey', subscription): the job-name chip.
 *
 * The parent `plugin-build` merges these into its subscription-row output
 * through its `subPluginIdFor(...)` delegation hook.
 *
 * Authored as source — compiled to `/main/build-jenkins/vue/index.js`.
 */
import { useI18nStore } from '@ligoj/host'
import enMessages from './i18n/en.js'
import frMessages from './i18n/fr.js'
import service from './service.js'

const features = {
  renderFeatures: service.renderFeatures,
  renderDetailsKey: service.renderDetailsKey,
}

export default {
  id: 'build-jenkins',
  label: 'Jenkins',
  // Declared dependency on the parent service-level plugin: it provides
  // the delegation hook that pulls our VNodes into subscription rows.
  requires: ['build'],
  install() {
    const i18n = useI18nStore()
    i18n.merge(enMessages, 'en')
    i18n.merge(frMessages, 'fr')
  },
  feature(action, ...args) {
    const fn = features[action]
    if (!fn) throw new Error(`Plugin "build-jenkins" has no feature "${action}"`)
    return fn(...args)
  },
  service,
  meta: { icon: 'mdi-jenkins', color: 'red-darken-1' },
}

export { service }
