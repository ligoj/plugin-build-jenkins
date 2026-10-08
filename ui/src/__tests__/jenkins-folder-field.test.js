import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mount } from '@vue/test-utils'
import JenkinsFolderField from '../fields/JenkinsFolderField.vue'

const stubs = { LigojTextarea: { props: ['modelValue'], emits: ['blur'], template: '<textarea class="ta" @blur="$emit(\'blur\')" />' } }
const mountField = (modelValue) => mount(JenkinsFolderField, {
  props: { parameter: { id: 'service:build:jenkins:template-folder' }, modelValue }, global: { stubs },
})

// The folder definition is saved trimmed: the value is trimmed when the field loses the focus (the submit click included).
describe('JenkinsFolderField', () => {
  beforeEach(() => { setActivePinia(createPinia()) })

  it('trims the definition on blur', async () => {
    const w = mountField('\n  {"name":"team"}  \n')
    await w.find('.ta').trigger('blur')
    expect(w.emitted('update:modelValue')).toEqual([['{"name":"team"}']])
  })

  it('does not emit when already trimmed or empty', async () => {
    const w = mountField('{"name":"team"}')
    await w.find('.ta').trigger('blur')
    const e = mountField(null)
    await e.find('.ta').trigger('blur')
    expect(w.emitted('update:modelValue')).toBeUndefined()
    expect(e.emitted('update:modelValue')).toBeUndefined()
  })
})
