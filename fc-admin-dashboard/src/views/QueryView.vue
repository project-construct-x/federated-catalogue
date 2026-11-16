<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import HelpTip from "@/components/HelpTip.vue";
import { examplesFor, type QueryExample } from "@/queryExamples";
import { api, apiPost } from "@/services/api";
import { ApiError, type QueryInfo, type QueryResults } from "@/types";

const loading = ref(true);
const running = ref(false);
const error = ref("");
const info = ref<QueryInfo | null>(null);
const query = ref("");
const results = ref<QueryResults | null>(null);
const selected = ref<QueryExample | null>(null);

const examples = computed(() => examplesFor(info.value?.queryLanguage));

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

const rowCount = computed(() => results.value?.totalCount ?? results.value?.items?.length ?? 0);

onMounted(async () => {
  try {
    info.value = await api<QueryInfo>("/admin/query/info");
    const first = examplesFor(info.value.queryLanguage)[0];
    if (first) loadExample(first);
    else query.value = info.value.exampleQuery || "SELECT ?subject ?predicate ?object WHERE { ?subject ?predicate ?object } LIMIT 25";
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : "Could not read the graph backend.";
    const first = examplesFor()[0];
    if (first) loadExample(first, false);
  } finally {
    loading.value = false;
  }
});

watch(query, (value) => {
  if (selected.value && value !== selected.value.query) selected.value = null;
});

function loadExample(example: QueryExample, clearError = true) {
  selected.value = example;
  query.value = example.query;
  if (clearError) error.value = "";
}

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
    <h1 class="text-h5 mb-2 d-inline-flex align-center">
      Graph query
      <HelpTip
        text="Read-only search of the claims graph. INSERT, DELETE, and other writes are rejected. A SELECT or MATCH without LIMIT is capped at 100 rows."
      />
    </h1>
    <p class="text-body-2 text-medium-emphasis mb-4">
      Run an example first. An empty table means the graph has no matching claims yet.
      You can also <router-link to="/explore" class="text-primary">walk the graph visually</router-link>.
      <span v-if="info?.backend"> Backend: {{ info.backend }}{{ info.queryLanguage ? `, ${info.queryLanguage}` : "" }}.</span>
      <a
        v-if="info?.documentation"
        :href="info.documentation"
        class="ml-1"
        target="_blank"
        rel="noopener noreferrer"
      >Language reference</a>
    </p>
    <v-progress-linear v-if="loading" indeterminate color="primary" class="mb-4" />
    <v-alert v-if="info && info.enabled === false" type="info" variant="tonal" class="mb-4" text="The graph store is disabled." />

    <div class="text-body-2 mb-2 d-inline-flex align-center">
      Example queries
      <HelpTip
        text="Clicking an example loads it into the box. Nothing is sent until you press Run query. Hover a chip to read what the example is looking for."
      />
    </div>
    <div class="d-flex flex-wrap mb-2">
      <v-tooltip v-for="example in examples" :key="example.id" location="bottom" max-width="320">
        <template #activator="{ props }">
          <v-chip
            v-bind="props"
            class="mr-2 mb-2"
            :color="selected?.id === example.id ? 'primary' : undefined"
            :variant="selected?.id === example.id ? 'flat' : 'outlined'"
            @click="loadExample(example)"
          >
            {{ example.title }}
          </v-chip>
        </template>
        <span class="text-body-2">{{ example.hint }}</span>
      </v-tooltip>
    </div>
    <p v-if="selected" class="text-body-2 text-medium-emphasis mb-4">{{ selected.hint }}</p>

    <v-textarea v-model="query" label="Query" auto-grow rows="8" class="query-text mb-2" />
    <v-btn color="primary" :loading="running" :disabled="!info?.enabled" @click="run">Run query</v-btn>
    <v-alert v-if="error" type="warning" variant="tonal" class="my-4" :text="error" />
    <div v-if="results" class="mt-4">
      <div class="text-body-2 text-medium-emphasis mb-2 d-inline-flex align-center">
        {{ rowCount }} rows
        <HelpTip text="Columns are the variables the query returned. Values that are not plain text are shown as JSON." />
      </div>
      <v-data-table :headers="headers" :items="rows" item-value="_row">
        <template #no-data>
          <div class="pa-4 text-body-2">
            No rows. The query ran, and the graph has nothing that matches.
            On a quiet catalogue, start with “{{ examples[0]?.title || "Anything stored" }}”.
          </div>
        </template>
      </v-data-table>
    </div>
  </div>
</template>

<style scoped>
.query-text :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.875rem;
}
</style>
