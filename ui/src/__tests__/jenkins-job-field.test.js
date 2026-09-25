import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mount } from '@vue/test-utils'
import { useI18nStore } from '@ligoj/host'
import JenkinsJobField from '../fields/JenkinsJobField.vue'
import pluginBuildJenkinsDef from '../index.js'

const JOB = { id: 'service:build:jenkins:job', type: 'TEXT', mandatory: false }
const FOLDER = 'service:build:jenkins:template-folder'
const stubs = {
  LigojTextField: { props: ['label', 'rules', 'modelValue'], template: '<input class="job" :data-label="label" :data-rules="rules.length" />' },
  LigojAutocomplete: { props: ['label', 'rules', 'modelValue'], template: '<input class="job" :data-label="label" :data-rules="rules.length" />' },
}
function mountField(props) {
  return mount(JenkinsJobField, { props: { parameter: JOB, modelValue: '', ...props }, global: { stubs } })
}

describe('JenkinsJobField — job required per mode', () => {
  beforeEach(() => { setActivePinia(createPinia()); useI18nStore().merge({ 'wizard.rule.required': 'Required' }, 'en'); pluginBuildJenkinsDef.install() })

  it('requires the job to link, whatever the parameter flag', () => {
    const w = mountField({ mode: 'link' })
    expect(w.find('.job').attributes('data-label')).toBe('Job *')
    expect(w.find('.job').attributes('data-rules')).toBe('1')
  })

  it('requires the job to create from a template job, not when a folder definition is given', () => {
    // CREATE mode always carries the live-validation rule; the required rule is added on top
    const w = mountField({ mode: 'create', formValues: {} })
    expect(w.find('.job').attributes('data-label')).toBe('Job *')
    expect(w.find('.job').attributes('data-rules')).toBe('2')
    const withFolder = mountField({ mode: 'create', formValues: { [FOLDER]: '{"name":"Admin"}' } })
    expect(withFolder.find('.job').attributes('data-label')).toBe('Job')
    expect(withFolder.find('.job').attributes('data-rules')).toBe('1')
    // A stale `mandatory` flag (row not yet relaxed by the plugin update) does not override the mode rule
    const stale = mountField({ mode: 'create', parameter: { ...JOB, mandatory: true }, formValues: { [FOLDER]: '{"name":"Admin"}' } })
    expect(stale.find('.job').attributes('data-label')).toBe('Job')
    expect(stale.find('.job').attributes('data-rules')).toBe('1')
  })
})
