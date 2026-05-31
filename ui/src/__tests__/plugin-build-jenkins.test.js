/*
 * Contract tests for plugin-build-jenkins (tool-level Jenkins plugin).
 */
import { describe, it, expect, beforeEach } from 'vitest'
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

  it('renderFeatures returns the Jenkins job link when url + job are set', () => {
    pluginBuildJenkinsDef.install()
    const vnodes = pluginBuildJenkinsDef.feature('renderFeatures', {
      node: { id: 'service:build:jenkins:1' },
      parameters: {
        'service:build:jenkins:url': 'https://ci.example.org',
        'service:build:jenkins:job': 'ligoj-build',
      },
    })
    expect(vnodes).toHaveLength(1)
    expect(vnodes[0].__v_isVNode).toBe(true)
    expect(vnodes[0].props.href).toBe('https://ci.example.org/job/ligoj-build')
    expect(vnodes[0].props.target).toBe('_blank')
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
})
