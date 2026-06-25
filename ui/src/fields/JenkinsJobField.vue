<template>
  <!-- Two render modes, mirroring the legacy `configureSubscriptionParameters`:

       - CREATE subscription mode: the job does not exist yet — a free-text
         field for the NEW job name with live validation (legacy
         `validateJobCreateMode`): the name must match the project key
         (`^(pkey|pkey-[a-z0-9]*)$`) and must NOT collide with an existing
         Jenkins job (`service/build/jenkins/<node>/job/<name>` → 200 = taken).
       - LINK / other modes: an autocomplete against the existing Jenkins
         jobs (`service/build/jenkins/<node>/<criteria>`), the legacy
         `registerXServiceSelect2(..., 'service/build/jenkins/')`.

       The project key is not currently threaded into the wizard's parameter
       fields, so the pattern check is best-effort: it only runs when a pkey
       is available (matching IdGroupField's behaviour) — the existence probe
       always runs. -->
  <v-text-field
    v-if="createMode"
    v-model="jobName"
    :label="paramLabel"
    :placeholder="pkey ? `${pkey}-my-job` : t('service:build:jenkins:job')"
    :error-messages="liveError ? [liveError] : []"
    :loading="checking"
    :rules="createRules"
    autocomplete="off"
    variant="outlined"
    density="compact"
    hide-details="auto"
  />

  <LigojAutocomplete
    v-else
    :model-value="modelValue"
    :label="paramLabel"
    :placeholder="t('service:build:jenkins:job-search')"
    :items="items"
    :loading="loading"
    item-title="name"
    item-value="id"
    variant="outlined"
    density="compact"
    clearable
    no-filter
    :rules="autocompleteRules"
    @update:search="onSearch"
    @update:model-value="(v) => emit('update:modelValue', v ?? '')"
  />
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useApi, useI18nStore, LigojAutocomplete } from '@ligoj/host'
import { useJobSearch } from './jobSearch.js'

const props = defineProps({
  modelValue: { type: [String, Number, null], default: null },
  parameter: { type: Object, required: true },
  formValues: { type: Object, default: () => ({}) },
  mode: { type: String, default: null },
  isNode: { type: Boolean, default: false },
  nodeId: { type: String, default: null },
  instanceNodeId: { type: String, default: null },
  project: { type: Object, default: null },
})
const emit = defineEmits(['update:modelValue'])

const { t } = useI18nStore()
const api = useApi()

const createMode = computed(() => !props.isNode && String(props.mode).toLowerCase() === 'create')

function tOrNull(key) { const v = t(key); return v === key ? null : v }
const paramLabel = computed(() => {
  const base = tOrNull(props.parameter?.id) ?? props.parameter?.id
  return `${base}${(props.parameter?.mandatory || props.parameter?.required) ? ' *' : ''}`
})

const REQUIRED_RULE = (v) => (v != null && String(v).trim() !== '') || (tOrNull('wizard.rule.required') ?? 'Required')
const isRequired = computed(() => !!(props.parameter?.mandatory || props.parameter?.required))

/* ------------- CREATE mode (new job name + live validation) ------------- */

const jobName = ref(typeof props.modelValue === 'string' ? props.modelValue : '')
const checking = ref(false)
const liveError = ref(null)
let pending = null

// Project key constraint — only enforced when a pkey is available. Mirrors the
// legacy `^(?:pkey|pkey-[a-z0-9]*)$`.
const pkey = computed(() => props.project?.pkey || props.formValues?.pkey || '')

async function recheck() {
  liveError.value = null
  const name = String(jobName.value || '').trim()
  if (!createMode.value || !name) return
  if (pkey.value) {
    const re = new RegExp(`^(?:${escapeRe(pkey.value)}|${escapeRe(pkey.value)}-[a-z0-9]*)$`)
    if (!re.test(name)) {
      liveError.value = t('service:build:jenkins:job-invalid', { pkey: pkey.value })
      return
    }
  }
  if (!props.instanceNodeId) return
  // Existence probe — race-protected so only the latest result is acted upon.
  const token = Symbol('exists')
  pending = token
  checking.value = true
  try {
    const resp = await api.get(
      `rest/service/build/jenkins/${encodeURIComponent(props.instanceNodeId)}/job/${encodeURIComponent(name)}`,
      { silent: true, raw: true },
    )
    if (pending !== token) return
    // 200 → the job already exists; anything else (404/error) → free to create.
    if (resp.ok) liveError.value = t('service:build:jenkins:job-exists', { name })
  } catch {
    /* transient: leave the field valid, the server-side check will re-run */
  } finally {
    if (pending === token) checking.value = false
  }
}

function escapeRe(s) { return String(s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&') }

// Keep the bound parameter value in sync with the typed name, and re-validate.
watch(jobName, (v) => { emit('update:modelValue', v); recheck() })
watch([createMode, pkey, () => props.instanceNodeId], () => { if (createMode.value) recheck() })

const createRules = computed(() => {
  const rules = isRequired.value ? [REQUIRED_RULE] : []
  // Surface the live validation result through the rules pipeline too, so the
  // form's overall validity (and the wizard's submit guard) reflects it.
  rules.push(() => !liveError.value || liveError.value)
  return rules
})

/* ------------- LINK / fallback mode (existing-job autocomplete) --------- */

const { items, loading, search } = useJobSearch(
  (node, criteria) => `rest/service/build/jenkins/${encodeURIComponent(node)}/${encodeURIComponent(criteria)}`,
)
function onSearch(term) { search(props.instanceNodeId, term) }
const autocompleteRules = computed(() => isRequired.value ? [REQUIRED_RULE] : [])
</script>
