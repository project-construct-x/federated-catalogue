<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api } from "@/services/api";
import { ApiError, type OntologySchemas } from "@/types";

const loading = ref(true);
const error = ref("");
const schemas = ref<OntologySchemas | null>(null);

const groups = [
  { title: "Ontologies", key: "ontologies" },
  { title: "Shapes", key: "shapes" },
  { title: "Vocabularies", key: "vocabularies" },
  { title: "JSON schemas", key: "jsonSchemas" },
  { title: "XML schemas", key: "xmlSchemas" }
] as const;

const rows = computed(() =>
  groups.flatMap((group) => (schemas.value?.[group.key] ?? []).map((id) => ({ type: group.title, id })))
);

const headers = [
  { title: "Type", key: "type" },
  { title: "Identifier", key: "id" }
];

onMounted(async () => {
  try {
    schemas.value = await api<OntologySchemas>("/schemas");
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : "Could not load schemas.";
  } finally {
    loading.value = false;
  }
});
</script>

<template>
  <div>
    <h1 class="text-h5 mb-4">Schemas</h1>
    <v-alert v-if="error" type="warning" variant="tonal" class="mb-4" :text="error" />
    <v-data-table :headers="headers" :items="rows" :loading="loading" item-value="id">
      <template #no-data>No schemas stored.</template>
    </v-data-table>
  </div>
</template>
