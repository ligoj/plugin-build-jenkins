<template>
  <!-- The `folder` parameter (CREATE mode only): the JSON definition of a folder tree to create INSTEAD of copying
       a template job — description, credentials and nested folders. The live check keeps a malformed definition
       from reaching the backend; the structure itself is validated server-side. -->
  <LigojTextarea
    :model-value="modelValue"
    :label="paramLabel"
    :placeholder="PLACEHOLDER"
    :hint="t('service:build:jenkins:template-folder-description')"
    :rules="rules"
    persistent-hint
    variant="outlined"
    density="compact"
    rows="6"
    auto-grow
    class="jk-folder"
    @update:model-value="(v) => emit('update:modelValue', v ?? '')"
  />
</template>

<script setup>
import { computed } from 'vue'
import { useI18nStore, LigojTextarea } from '@ligoj/host'
import { folderDefinitionError } from './folderDefinition.js'

const props = defineProps({
  modelValue: { type: [String, null], default: null },
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

const PLACEHOLDER = '{\n  "description": "…",\n  "credentials": [{ "id": "…", "stapler-class": "…", "attributes": {} }],\n  "folders": [{ "name": "…" }]\n}'
const paramLabel = computed(() => t(props.parameter.id))
const rules = [(v) => { const key = folderDefinitionError(v); return key ? t(key) : true }]
</script>

<style scoped>
.jk-folder :deep(textarea) { font-family: var(--mono, ui-monospace, monospace); font-size: 12.5px; }
</style>
