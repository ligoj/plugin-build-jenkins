/*
 * Remote job-search helper shared by the Jenkins autocompletes
 * (JenkinsJobField in LINK mode, JenkinsTemplateJobField in CREATE mode).
 *
 * Ports the legacy `registerXServiceSelect2(..., 'service/build/jenkins/')`
 * and `.../template/` remote selects: a search-as-you-type query against the
 * Jenkins job search endpoints, resolving the matched `Job` rows.
 *
 * `urlFor(node, criteria)` builds the REST path so the SAME loader serves the
 * job search and the template search (the only thing that differs is the path
 * prefix). The fetch is deferred until the user actually types: the backend
 * endpoint is path-shaped (`{node}/{criteria}`) so an empty criteria is not a
 * valid request — opening the menu without typing simply shows nothing.
 */
import { ref } from 'vue'
import { useApi } from '@ligoj/host'

export function useJobSearch(urlFor) {
  const api = useApi()
  const items = ref([])
  const loading = ref(false)
  // Distinguishes "same query, skip" from the initial state; a stale-response
  // guard so only the latest in-flight search updates the list.
  let lastQuery = null
  let pending = null

  async function search(node, term) {
    const q = (term || '').trim()
    if (q === lastQuery) return
    lastQuery = q
    // The path endpoint needs a non-empty criteria segment — defer until typed.
    if (!node || !q) {
      items.value = []
      return
    }
    const token = Symbol('search')
    pending = token
    loading.value = true
    try {
      const data = await api.get(urlFor(node, q), { silent: true })
      if (pending !== token) return
      const list = Array.isArray(data) ? data : (data?.data || [])
      items.value = list.map((j) => ({ id: j.id ?? j.name, name: j.name ?? j.id, description: j.description }))
    } catch {
      if (pending === token) items.value = []
    } finally {
      if (pending === token) loading.value = false
    }
  }

  return { items, loading, search }
}
