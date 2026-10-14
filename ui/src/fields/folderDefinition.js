/**
 * Client-side check of a Jenkins folder definition (the `service:build:jenkins:template-folder` parameter). Mirrors the
 * backend rules so the user gets the message while typing: valid JSON object, a name on every nested folder, an id
 * and a class on every credential, a permissions list or a permission template on every role. An empty value is valid: the template job is used
 * instead.
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
  // Roles: keyed by group name, each with exactly one of a non-empty permissions list or a permission template name
  const roles = folder.roles
  if (roles != null && (typeof roles !== 'object' || Array.isArray(roles))) return 'service:build:jenkins:template-folder-invalid-role'
  for (const role of Object.values(roles || {})) {
    const permissions = Array.isArray(role?.permissions) && role.permissions.some((perm) => String(perm ?? '').trim())
    const template = !!String(role?.template ?? '').trim()
    if (permissions === template) return 'service:build:jenkins:template-folder-invalid-role'
  }
  for (const f of folder.folders || []) {
    const error = check(f, false)
    if (error) return error
  }
  return null
}

/**
 * Short text of a folder definition, for the subscription status tooltip: the folders, roles and credentials it
 * creates, counted through the whole tree ("3 folders · 3 roles · 3 credentials"), the empty counts left out.
 *
 * @param {string|null|undefined} value The stored definition.
 * @param {Function} t The i18n translation function.
 * @returns {string|null} The summary, or null for a blank or unreadable value.
 */
export function folderDefinitionSummary(value, t) {
  const text = String(value ?? '').trim()
  let root
  try { root = text ? JSON.parse(text) : null } catch { return null }
  if (!root || typeof root !== 'object' || Array.isArray(root)) return null
  const counts = { folders: 0, roles: 0, credentials: 0 }
  const visit = (folder) => {
    counts.roles += Object.keys(folder?.roles || {}).length
    counts.credentials += (folder?.credentials || []).length
    for (const f of folder?.folders || []) {
      counts.folders++
      visit(f)
    }
  }
  visit(root)
  const parts = Object.entries(counts).filter(([, n]) => n)
    .map(([kind, n]) => t(`service:build:jenkins:template-folder-${kind}`, { count: n }, n))
  return parts.length ? parts.join(' · ') : t('service:build:jenkins:template-folder-empty')
}
