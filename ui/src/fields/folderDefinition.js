/**
 * Client-side check of a Jenkins folder definition (the `service:build:jenkins:template-folder` parameter). Mirrors the
 * backend rules so the user gets the message while typing: valid JSON object, a name on every nested folder, an id
 * and a class on every credential. An empty value is valid: the template job is used instead.
 *
 * @param {string|null|undefined} value The typed definition.
 * @returns {string|null} The i18n key of the first problem, or null.
 */
export function folderDefinitionError(value) {
  const text = String(value ?? '').trim()
  if (!text) return null
  let root
  try { root = JSON.parse(text) } catch { return 'service:build:jenkins:template-folder-invalid-json' }
  if (!root || typeof root !== 'object' || Array.isArray(root)) return 'service:build:jenkins:template-folder-invalid-json'
  return check(root, true)
}

function check(folder, isRoot) {
  if (!isRoot && !String(folder?.name ?? '').trim()) return 'service:build:jenkins:template-folder-invalid-name'
  for (const c of folder.credentials || []) {
    if (!String(c?.id ?? '').trim() || !String(c?.['stapler-class'] ?? '').trim()) return 'service:build:jenkins:template-folder-invalid-credential'
  }
  for (const f of folder.folders || []) {
    const error = check(f, false)
    if (error) return error
  }
  return null
}
