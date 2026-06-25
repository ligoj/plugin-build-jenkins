<template>
  <!-- The `template-job` parameter (CREATE mode only): an autocomplete against
       the Jenkins TEMPLATE jobs, mirroring the legacy
       `registerXServiceSelect2(..., 'service/build/jenkins/template/')`. The
       picked template is cloned server-side into the new job on subscribe. -->
  <LigojAutocomplete
    :model-value="modelValue"
    :label="paramLabel"
    :placeholder="t('service:build:jenkins:template-job-search')"
    :items="items"
    :loading="loading"
    item-title="name"
    item-value="id"
    variant="outlined"
    density="compact"
    clearable
    no-filter
    :rules="rules"
    @update:search="onSearch"
    @update:model-value="(v) => emit('update:modelValue', v ?? '')"
  />
</template>

<script setup>
import { computed } from 'vue'
import { useI18nStore, LigojAutocomplete } from '@ligoj/host'
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

function tOrNull(key) { const v = t(key); return v === key ? null : v }
const paramLabel = computed(() => {
  const base = tOrNull(props.parameter?.id) ?? props.parameter?.id
  return `${base}${(props.parameter?.mandatory || props.parameter?.required) ? ' *' : ''}`
})

const { items, loading, search } = useJobSearch(
  (node, criteria) => `rest/service/build/jenkins/template/${encodeURIComponent(node)}/${encodeURIComponent(criteria)}`,
)
function onSearch(term) { search(props.instanceNodeId, term) }

const REQUIRED_RULE = (v) => (v != null && String(v).trim() !== '') || (tOrNull('wizard.rule.required') ?? 'Required')
const rules = computed(() => (props.parameter?.mandatory || props.parameter?.required) ? [REQUIRED_RULE] : [])
</script>
