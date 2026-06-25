/*
 * Contract tests for plugin-build-jenkins (tool-level Jenkins plugin).
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useI18nStore } from '@ligoj/host'
import pluginBuildJenkinsDef from '../index.js'
import JenkinsJobField from '../fields/JenkinsJobField.vue'
import JenkinsTemplateJobField from '../fields/JenkinsTemplateJobField.vue'

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
    // A real mdi glyph — `mdi-jenkins` was removed from the font, so it must
    // not regress to an invisible icon.
    expect(vnodes[0].children.default().children.default()).toBe('mdi-home')
    // The second VNode is the build action: a click handler, no href.
    expect(vnodes[1].props.href).toBeUndefined()
    expect(typeof vnodes[1].props.onClick).toBe('function')
    expect(vnodes[1].children.default()).toBeTruthy()
  })

  it('renderFeatures uses the Jenkins job description as the home-link tooltip when available', () => {
    pluginBuildJenkinsDef.install()
    const sub = {
      id: 8,
      parameters: { 'service:build:jenkins:url': 'https://ci.example.org', 'service:build:jenkins:job': 'ligoj-build' },
      data: { job: { name: 'Ligoj CI', description: 'Nightly pipeline' } },
    }
    const home = pluginBuildJenkinsDef.feature('renderFeatures', sub)[0]
    expect(home.props.title).toBe('Nightly pipeline')
    // Without a description it falls back to the generic "Job" label.
    const noDesc = pluginBuildJenkinsDef.feature('renderFeatures', {
      parameters: sub.parameters,
    })[0]
    expect(noDesc.props.title).toBe('Job')
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

  it('renderDetailsFeatures renders just the live status icon in single-job mode', () => {
    pluginBuildJenkinsDef.install()
    const out = pluginBuildJenkinsDef.feature('renderDetailsFeatures', {
      parameters: { 'service:build:jenkins:url': 'https://ci.example.org/', 'service:build:jenkins:job': 'ligoj-build' },
      // lastBuild is a TIMESTAMP, not a build number — no execution link.
      data: { job: { status: 'blue', building: false, lastBuild: 1782383383781 } },
    })
    expect(out).toHaveLength(1)
    const icon = out[0]
    expect(icon.props.color).toBe('success')
    expect(icon.props.title).toBe('Success')
    expect(icon.children.default()).toBe('mdi-check-circle')
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

  it('renderFeatures appends a help link when the help URL parameter is set', () => {
    pluginBuildJenkinsDef.install()
    const vnodes = pluginBuildJenkinsDef.feature('renderFeatures', {
      id: 9,
      parameters: {
        'service:build:jenkins:url': 'https://ci.example.org',
        'service:build:jenkins:job': 'ligoj-build',
        'service:build:help': 'https://docs.example.org/jenkins',
      },
    })
    expect(vnodes).toHaveLength(3) // home + build + help
    const help = vnodes[2]
    expect(help.props.href).toBe('https://docs.example.org/jenkins')
    expect(help.props.target).toBe('_blank')
    expect(help.props.title).toBe('Help')
  })

  it('renderDetailsFeatures renders one link + status icon per branch in multi-branch mode', () => {
    pluginBuildJenkinsDef.install()
    const out = pluginBuildJenkinsDef.feature('renderDetailsFeatures', {
      parameters: { 'service:build:jenkins:url': 'https://ci.example.org/', 'service:build:jenkins:job': 'ligoj' },
      data: {
        job: {
          id: 'ligoj',
          status: 'blue',
          jobs: [
            { id: 'main', name: 'main', status: 'blue', building: false },
            { id: 'PR-7', name: 'feature/x', status: 'red', building: false, pullRequestBranch: true },
          ],
        },
      },
    })
    expect(out).toHaveLength(2)
    // Each entry is a <span> wrapping [branchLink, statusIcon].
    const [branchSpan, prSpan] = out
    expect(branchSpan.type).toBe('span')

    const branchLink = branchSpan.children[0]
    // Nested folder URL: each path segment gets its own /job/ traversal.
    expect(branchLink.props.href).toBe('https://ci.example.org/job/ligoj/job/main/')
    expect(branchLink.children.default().children.default()).toBe('mdi-source-branch')
    expect(branchSpan.children[1].props.color).toBe('success') // branch's OWN status

    const prLink = prSpan.children[0]
    expect(prLink.props.href).toBe('https://ci.example.org/job/ligoj/view/change-requests/job/feature%2Fx/')
    expect(prLink.children.default().children.default()).toBe('mdi-source-pull')
    expect(prSpan.children[1].props.color).toBe('error') // PR's OWN status
  })

  it('stops polling once a refresh reports the job is no longer building', async () => {
    vi.useFakeTimers()
    pluginBuildJenkinsDef.install()
    const orig = global.fetch
    global.fetch = vi.fn((url, opts) => {
      const method = opts?.method || 'GET'
      // The build trigger (POST) succeeds…
      if (method === 'POST') return Promise.resolve({ ok: true, status: 204, headers: { get: () => null } })
      // …and every status refresh reports the job is NOT building.
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ data: { job: { status: 'blue', building: false, lastBuild: 1782383383781 } } }),
      })
    })
    try {
      const sub = {
        id: 555,
        parameters: { 'service:build:jenkins:url': 'https://ci.example.org', 'service:build:jenkins:job': 'ligoj' },
      }
      // Trigger the build → the row goes busy and the poll loop starts.
      pluginBuildJenkinsDef.feature('renderFeatures', sub)[1].props.onClick()
      // Drive the POST resolution + the refresh ticks through the grace window.
      await vi.advanceTimersByTimeAsync(20000)
      // The build is not building → polling must terminate: the button is no
      // longer busy and no interval is left running.
      const after = pluginBuildJenkinsDef.feature('renderFeatures', sub)
      expect(after[1].props.disabled).toBeFalsy()
      expect(vi.getTimerCount()).toBe(0)
    } finally {
      global.fetch = orig
      vi.useRealTimers()
    }
  })

  it('parameterField returns the custom job / template-job inputs, null otherwise', () => {
    const job = pluginBuildJenkinsDef.feature('parameterField', {
      parameter: { id: 'service:build:jenkins:job' }, mode: 'link', isNode: false,
    })
    expect(job).toBe(JenkinsJobField)
    const tpl = pluginBuildJenkinsDef.feature('parameterField', {
      parameter: { id: 'service:build:jenkins:template-job' }, mode: 'create', isNode: false,
    })
    expect(tpl).toBe(JenkinsTemplateJobField)
    // Other parameters keep the wizard's default type-based rendering.
    expect(pluginBuildJenkinsDef.feature('parameterField', {
      parameter: { id: 'service:build:jenkins:url' }, isNode: false,
    })).toBeNull()
    // Node-config editing (edit-node / create-node) falls back to defaults.
    expect(pluginBuildJenkinsDef.feature('parameterField', {
      parameter: { id: 'service:build:jenkins:job' }, isNode: true,
    })).toBeNull()
  })
})
