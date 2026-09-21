import { describe, it, expect } from 'vitest'
import { folderDefinitionError } from '../fields/folderDefinition.js'

describe('folderDefinitionError', () => {
  it('accepts an empty value (the template job is used) and a complete tree, roles ignored', () => {
    expect(folderDefinitionError('')).toBeNull()
    expect(folderDefinitionError(null)).toBeNull()
    expect(folderDefinitionError(JSON.stringify({
      description: 'd', roles: { dev: {} },
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
})
