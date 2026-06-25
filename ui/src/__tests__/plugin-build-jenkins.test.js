/*
 * Contract tests for plugin-build-jenkins (tool-level Jenkins plugin).
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useI18nStore } from '@ligoj/host'
import pluginBuildJenkinsDef from '../index.js'

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('plugin-build-jenkins contract', () => {
  it('exposes a valid tool-level manifest', () => {
    expect(pluginBuildJenkinsDef.id).toBe('build-jenkins')
    expect(typeof pluginBuildJenkinsDef.label).toBe('string')
    expect(pluginBuildJenkinsDef.requires).toEqual(['build'])
    expect(pluginBuildJenkinsDef.routes).toBeUndefined()
    expect(typeof pluginBuildJenkinsDef.install).toBe('function')
    expect(typeof pluginBuildJenkinsDef.feature).toBe('function')
    expect(pluginBuildJenkinsDef.service).toBeTypeOf('object')
    expect(pluginBuildJenkinsDef.meta).toMatchObject({ icon: expect.any(String), color: expect.any(String) })
  })

  it('merges Jenkins parameter i18n on install', () => {
    const i18n = useI18nStore()
    pluginBuildJenkinsDef.install()
    expect(i18n.t('service:build:jenkins:url')).toBe('Jenkins base URL')
    expect(i18n.t('service:build:jenkins:job')).toBe('Job')
  })

  it('throws for an unknown feature', () => {
    expect(() => pluginBuildJenkinsDef.feature('nope')).toThrow(/no feature "nope"/)
  })

  it('renderFeatures returns the Jenkins job link (+ build action) when url + job are set', () => {
    pluginBuildJenkinsDef.install()
    const vnodes = pluginBuildJenkinsDef.feature('renderFeatures', {
      id: 7,
      node: { id: 'service:build:jenkins:1' },
      parameters: {
        'service:build:jenkins:url': 'https://ci.example.org',
        'service:build:jenkins:job': 'ligoj-build',
      },
    })
    expect(vnodes).toHaveLength(2)
    expect(vnodes[0].__v_isVNode).toBe(true)
    expect(vnodes[0].props.href).toBe('https://ci.example.org/job/ligoj-build')
    expect(vnodes[0].props.target).toBe('_blank')
    // The second VNode is the build action: a click handler, no href.
    expect(vnodes[1].props.href).toBeUndefined()
    expect(typeof vnodes[1].props.onClick).toBe('function')
    expect(vnodes[1].children.default()).toBeTruthy()
  })

  it('renderFeatures omits the build action without a subscription id', () => {
    pluginBuildJenkinsDef.install()
    const vnodes = pluginBuildJenkinsDef.feature('renderFeatures', {
      parameters: {
        'service:build:jenkins:url': 'https://ci.example.org',
        'service:build:jenkins:job': 'ligoj-build',
      },
    })
    expect(vnodes).toHaveLength(1)
    expect(vnodes[0].props.href).toBe('https://ci.example.org/job/ligoj-build')
  })

  it('the build action POSTs to the ligoj build endpoint and marks the row busy', () => {
    pluginBuildJenkinsDef.install()
    const calls = []
    const orig = global.fetch
    // A never-resolving fetch keeps the POST in flight: we assert the
    // synchronous effects (the request was issued, the row went busy) without
    // letting the subsequent poll loop schedule a timer.
    global.fetch = vi.fn((url, opts) => {
      calls.push({ url, method: opts?.method || 'GET' })
      return new Promise(() => {})
    })
    try {
      const sub = {
        id: 42,
        parameters: { 'service:build:jenkins:url': 'https://ci.example.org', 'service:build:jenkins:job': 'ligoj-build' },
      }
      const buildBtn = pluginBuildJenkinsDef.feature('renderFeatures', sub)[1]
      buildBtn.props.onClick()
      // useApi().post invokes fetch synchronously (before its first await).
      expect(global.fetch).toHaveBeenCalledTimes(1)
      expect(calls[0].method).toBe('POST')
      expect(calls[0].url).toContain('rest/service/build/jenkins/build/42')
      // The row immediately reflects the in-flight build (disabled, no re-trigger).
      const after = pluginBuildJenkinsDef.feature('renderFeatures', sub)[1]
      expect(after.props.disabled).toBe(true)
    } finally {
      global.fetch = orig
    }
  })

  it('renderFeatures returns an empty list when url or job is missing', () => {
    pluginBuildJenkinsDef.install()
    expect(pluginBuildJenkinsDef.feature('renderFeatures', {
      parameters: { 'service:build:jenkins:url': 'https://ci.example.org' },
    })).toEqual([])
    expect(pluginBuildJenkinsDef.feature('renderFeatures', {})).toEqual([])
  })

  it('renderDetailsKey returns the job chip when present', () => {
    pluginBuildJenkinsDef.install()
    const vnode = pluginBuildJenkinsDef.feature('renderDetailsKey', {
      parameters: { 'service:build:jenkins:job': 'ligoj-build' },
    })
    expect(vnode).toBeTruthy()
    expect(vnode.__v_isVNode).toBe(true)
  })

  it('renderDetailsKey returns null without a job', () => {
    pluginBuildJenkinsDef.install()
    expect(pluginBuildJenkinsDef.feature('renderDetailsKey', { parameters: {} })).toBeNull()
  })

  it('renderDetailsKey shows the job display name with the description as tooltip', () => {
    pluginBuildJenkinsDef.install()
    const vnode = pluginBuildJenkinsDef.feature('renderDetailsKey', {
      parameters: { 'service:build:jenkins:job': 'ligoj-build' },
      data: { job: { name: 'Ligoj CI', description: 'Main pipeline' } },
    })
    expect(vnode.props.title).toBe('Main pipeline') // description as v-tooltip
    const kids = vnode.children.default()
    expect(kids[kids.length - 1]).toBe('Ligoj CI') // display name, not the job path
  })

  it('renderDetailsKey falls back to the job path + field label without live data', () => {
    pluginBuildJenkinsDef.install()
    const vnode = pluginBuildJenkinsDef.feature('renderDetailsKey', {
      parameters: { 'service:build:jenkins:job': 'ligoj-build' },
    })
    expect(vnode.props.title).toBe('Job')
    expect(vnode.children.default().pop()).toBe('ligoj-build')
  })

  it('renderDetailsFeatures returns null until live job data arrives', () => {
    pluginBuildJenkinsDef.install()
    expect(pluginBuildJenkinsDef.feature('renderDetailsFeatures', { parameters: {} })).toBeNull()
  })

  it('renderDetailsFeatures renders the status icon (colour + tooltip) and last-build link', () => {
    pluginBuildJenkinsDef.install()
    const out = pluginBuildJenkinsDef.feature('renderDetailsFeatures', {
      parameters: { 'service:build:jenkins:url': 'https://ci.example.org/', 'service:build:jenkins:job': 'ligoj-build' },
      data: { job: { status: 'blue', building: false, lastBuild: 42 } },
    })
    const icon = out[0]
    expect(icon.props.color).toBe('success')
    expect(icon.props.title).toBe('Success')
    expect(icon.children.default()).toBe('mdi-check-circle')
    const link = out[1]
    expect(link.props.href).toBe('https://ci.example.org/job/ligoj-build/42/')
    expect(link.props.target).toBe('_blank')
    expect(link.props.title).toBe('Last build #42')
  })

  it('renderDetailsFeatures shows a building spinner with a "(Building)" tooltip', () => {
    pluginBuildJenkinsDef.install()
    const out = pluginBuildJenkinsDef.feature('renderDetailsFeatures', {
      parameters: { 'service:build:jenkins:url': 'https://ci.example.org', 'service:build:jenkins:job': 'ligoj-build' },
      data: { job: { status: 'red', building: true } },
    })
    const icon = out[0]
    expect(icon.children.default()).toBe('mdi-sync')
    expect(String(icon.props.class)).toContain('mdi-spin')
    expect(icon.props.color).toBe('error')
    expect(icon.props.title).toBe('Failure (Building)')
    expect(out).toHaveLength(1) // no lastBuild → status only
  })
})
