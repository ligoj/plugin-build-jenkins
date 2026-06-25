/*
 * Plugin "build-jenkins" — Jenkins implementation of plugin-build.
 *
 * Tool-level plugin: lives at `service:build:jenkins` in the node tree.
 * It augments the parent `plugin-build` via:
 *   - i18n: Jenkins parameter labels (url, user, api-token, job, …) for
 *     the subscribe wizard's auto-rendered parameter form.
 *   - feature('renderFeatures', subscription): the Jenkins project home link.
 *   - feature('renderDetailsKey', subscription): the job display-name chip
 *     (description as tooltip).
 *   - feature('renderDetailsFeatures', subscription): the live status icon
 *     (+ building spinner) and a link to the last execution, or one link +
 *     status per branch in multi-branch / pull-request mode.
 *   - feature('parameterField', ctx): custom subscribe-wizard inputs for the
 *     job (autocomplete / validated create-mode name) and template-job params.
 *
 * The parent `plugin-build` merges the render hooks into its subscription-row
 * output through its `subPluginIdFor(...)` delegation hook; the subscribe
 * wizard calls `parameterField` directly to resolve a custom field component.
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
  renderDetailsFeatures: service.renderDetailsFeatures,
  parameterField: service.parameterField,
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
  // mdi dropped the `jenkins` glyph (trademark); use a build/CI gear in the
  // Jenkins brand colour. The parent `build` uses `mdi-cog-sync`.
  meta: { icon: 'mdi-cog', color: 'red-darken-1' },
}

export { service }
