<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api, apiPost } from "@/services/api";
import { ApiError, type QueryInfo, type QueryResults } from "@/types";

const loading = ref(true);
const running = ref(false);
const error = ref("");
const info = ref<QueryInfo | null>(null);
const query = ref("");
const results = ref<QueryResults | null>(null);

const headers = computed(() => {
  const keys = new Set<string>();
  for (const item of results.value?.items ?? []) {
    Object.keys(item).forEach((key) => keys.add(key));
  }
  return [...keys].map((key) => ({ title: key, key }));
});

const rows = computed(() =>
  (results.value?.items ?? []).map((item, index) => ({
    ...Object.fromEntries(Object.entries(item).map(([key, value]) => [key, formatValue(value)])),
    _row: index
  }))
);

onMounted(async () => {
  try {
    info.value = await api<QueryInfo>("/admin/query/info");
    query.value = info.value.exampleQuery || "SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 25";
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : "Could not read the graph backend.";
  } finally {
    loading.value = false;
  }
});

async function run() {
  if (!info.value?.contentType) {
    error.value = "The graph backend is not available.";
    return;
  }
  running.value = true;
  error.value = "";
  results.value = null;
  try {
    results.value = await apiPost<QueryResults>("/admin/query", query.value, info.value.contentType);
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : "The query failed.";
  } finally {
    running.value = false;
  }
}

function formatValue(value: unknown): string {
  if (value == null) return "";
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
    return String(value);
  }
  return JSON.stringify(value);
}
</script>

<template>
  <div>
    <h1 class="text-h5 mb-2">Graph query</h1>
    <p class="text-body-2 text-medium-emphasis mb-4">
      Read-only query against the catalogue graph
      <span v-if="info?.backend"> ({{ info.backend }}{{ info.queryLanguage ? `, ${info.queryLanguage}` : "" }})</span>.
    </p>
    <v-progress-linear v-if="loading" indeterminate color="primary" class="mb-4" />
    <v-alert v-if="info && info.enabled === false" type="info" variant="tonal" class="mb-4" text="The graph store is disabled." />
    <v-textarea v-model="query" label="Query" auto-grow rows="6" class="mb-2" />
    <v-btn color="primary" :loading="running" :disabled="!info?.enabled" @click="run">Run query</v-btn>
    <v-alert v-if="error" type="warning" variant="tonal" class="my-4" :text="error" />
    <div v-if="results" class="mt-4">
      <div class="text-body-2 text-medium-emphasis mb-2">{{ results.totalCount ?? results.items?.length ?? 0 }} rows</div>
      <v-data-table :headers="headers" :items="rows" item-value="_row">
        <template #no-data>The query returned no rows.</template>
      </v-data-table>
    </div>
  </div>
</template>
