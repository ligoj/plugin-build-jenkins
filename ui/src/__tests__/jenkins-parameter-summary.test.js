/*
 * `parameterSummary` feature: the folder definition in the subscription status tooltip as what it creates, counted
 * through the whole tree, instead of the raw JSON.
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useI18nStore } from '@ligoj/host'
import def from '../index.js'

const FOLDER = 'service:build:jenkins:template-folder'
const summary = (parameter, value) => def.feature('parameterSummary', { parameter, value })
const TREE = JSON.stringify({
  description: 'Root',
  roles: { admin: { template: 'corporate-admin' } },
  credentials: [{ id: 'git', 'stapler-class': 'x.Git' }],
  folders: [
    { name: 'a', roles: { dev: { permissions: ['hudson.model.Item.Build'] }, test: { template: 'std' } },
      credentials: [{ id: 'aws', 'stapler-class': 'x.Aws' }, { id: 'ssh', 'stapler-class': 'x.Ssh' }],
      folders: [{ name: 'a.1' }] },
    { name: 'b' },
  ],
})

describe('build-jenkins parameterSummary', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    def.install()
    useI18nStore().setLocale('en')
  })

  it('counts the folders, roles and credentials of the whole tree', () => {
    expect(summary(FOLDER, TREE)).toBe('3 folders · 3 roles · 3 credentials')
  })

  it('shows only what the definition holds, in the singular for one', () => {
    expect(summary(FOLDER, '{"folders":[{"name":"a","roles":{"dev":{"template":"std"}}}]}')).toBe('1 folder · 1 role')
    expect(summary(FOLDER, '{"description":"Only a description"}')).toBe('No folder, role or credential')
  })

  it('translates the summary', () => {
    useI18nStore().setLocale('fr')
    expect(summary(FOLDER, TREE)).toBe('3 dossiers · 3 rôles · 3 identifiants')
  })

  it('leaves the other parameters and an unreadable value to the default display', () => {
    expect(summary('service:build:jenkins:job', 'team/app')).toBeNull()
    expect(summary(FOLDER, '{ not json')).toBeNull()
    expect(summary(FOLDER, '[]')).toBeNull()
    expect(summary(FOLDER, '')).toBeNull()
  })
})
