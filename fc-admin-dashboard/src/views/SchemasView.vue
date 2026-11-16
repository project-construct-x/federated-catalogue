<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import HelpTip from "@/components/HelpTip.vue";
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
  { title: "Type", key: "type", hint: "Which registry the identifier belongs to: ontology, shape, vocabulary, JSON schema, or XML schema." },
  { title: "Identifier", key: "id", hint: "Name the catalogue uses to look the schema up." }
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
    <h1 class="text-h5 mb-2 d-inline-flex align-center">
      Schemas
      <HelpTip text="Artefacts used when credentials are checked. An empty table means this instance has none registered." />
    </h1>
    <p class="text-body-2 text-medium-emphasis mb-4">
      Ontologies describe terms, shapes validate credentials, and vocabularies supply shared lists.
    </p>
    <v-alert v-if="error" type="warning" variant="tonal" class="mb-4" :text="error" />
    <v-data-table :headers="headers" :items="rows" :loading="loading" item-value="id">
      <template v-for="header in headers" :key="header.key" #[`header.${header.key}`]="{ column, getSortIcon }">
        <div class="v-data-table-header__content">
          <span class="d-inline-flex align-center">
            {{ column.title }}
            <HelpTip :text="header.hint" />
          </span>
          <v-icon v-if="column.sortable !== false" :icon="getSortIcon(column)" class="v-data-table-header__sort-icon" />
        </div>
      </template>
      <template #no-data>
        <div class="pa-4 text-body-2">
          <template v-if="error">Could not list schemas.</template>
          <template v-else>No schemas stored on this instance.</template>
        </div>
      </template>
    </v-data-table>
  </div>
</template>
