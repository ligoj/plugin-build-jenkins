import { describe, it, expect } from 'vitest'
import { folderDefinitionError } from '../fields/folderDefinition.js'

describe('folderDefinitionError', () => {
  it('accepts an empty value (the template job is used) and a complete tree', () => {
    expect(folderDefinitionError('')).toBeNull()
    expect(folderDefinitionError(null)).toBeNull()
    expect(folderDefinitionError(JSON.stringify({
      description: 'd', roles: { dev: { permissions: ['hudson.model.Item.Build'] } },
      credentials: [{ id: 'c', 'stapler-class': 'x.Y', attributes: { secret: 's' } }],
      folders: [{ name: 'a', folders: [{ name: 'b' }] }],
    }))).toBeNull()
  })
  it('rejects what is not a JSON object', () => {
    expect(folderDefinitionError('{oops')).toBe('service:build:jenkins:template-folder-invalid-json')
    expect(folderDefinitionError('[]')).toBe('service:build:jenkins:template-folder-invalid-json')
    expect(folderDefinitionError('3')).toBe('service:build:jenkins:template-folder-invalid-json')
  })
  it('requires a name on nested folders, at any depth, but not on the root', () => {
    expect(folderDefinitionError('{"name":""}')).toBeNull()
    expect(folderDefinitionError('{"folders":[{"description":"x"}]}')).toBe('service:build:jenkins:template-folder-invalid-name')
    expect(folderDefinitionError('{"folders":[{"name":"a","folders":[{}]}]}')).toBe('service:build:jenkins:template-folder-invalid-name')
  })
  it('requires an id and a class on every credential', () => {
    expect(folderDefinitionError('{"credentials":[{"id":"c"}]}')).toBe('service:build:jenkins:template-folder-invalid-credential')
    expect(folderDefinitionError('{"folders":[{"name":"a","credentials":[{"stapler-class":"x"}]}]}')).toBe('service:build:jenkins:template-folder-invalid-credential')
  })

  it('accepts a permission template instead of a permissions list, never both', () => {
    expect(folderDefinitionError('{"roles":{"dev":{"template":"developer"}}}')).toBeNull()
    expect(folderDefinitionError('{"roles":{"dev":{"template":" "}}}')).toBe('service:build:jenkins:template-folder-invalid-role')
    expect(folderDefinitionError('{"roles":{"dev":{"template":"developer","permissions":["hudson.model.Item.Build"]}}}')).toBe('service:build:jenkins:template-folder-invalid-role')
  })

  it('requires a permissions list on every role, keyed by group', () => {
    expect(folderDefinitionError('{"roles":{"dev":{"permissions":["hudson.model.Item.Build"]}}}')).toBeNull()
    expect(folderDefinitionError('{"roles":{"dev":{}}}')).toBe('service:build:jenkins:template-folder-invalid-role')
    expect(folderDefinitionError('{"roles":{"dev":{"permissions":[]}}}')).toBe('service:build:jenkins:template-folder-invalid-role')
    expect(folderDefinitionError('{"roles":[]}')).toBe('service:build:jenkins:template-folder-invalid-role')
    expect(folderDefinitionError('{"folders":[{"name":"a","roles":{"dev":{"permissions":[""]}}}]}')).toBe('service:build:jenkins:template-folder-invalid-role')
  })
})
