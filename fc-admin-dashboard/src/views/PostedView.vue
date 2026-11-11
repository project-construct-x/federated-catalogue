<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api } from "@/services/api";
import { ApiError, type PostedAsset, type PostedAssetContent, type PostedAssetPage } from "@/types";

const loading = ref(true);
const error = ref("");
const assets = ref<PostedAsset[]>([]);
const total = ref(0);
const selected = ref<PostedAssetContent | null>(null);
const contentError = ref("");
const contentLoading = ref(false);

const headers = [
  { title: "Id", key: "id" },
  { title: "Status", key: "status" },
  { title: "Issuer", key: "issuer" },
  { title: "Type", key: "contentType" },
  { title: "Uploaded", key: "uploadDatetime" }
];

onMounted(async () => {
  try {
    const page = await api<PostedAssetPage>("/admin/posted-assets?offset=0&limit=100");
    assets.value = page.items ?? [];
    total.value = page.totalCount ?? assets.value.length;
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : "Could not load posted assets.";
  } finally {
    loading.value = false;
  }
});

async function openAsset(asset: PostedAsset) {
  if (!asset.id) return;
  contentLoading.value = true;
  contentError.value = "";
  selected.value = null;
  try {
    selected.value = await api<PostedAssetContent>(
      `/admin/posted-assets/content?id=${encodeURIComponent(asset.id)}`
    );
  } catch (cause) {
    contentError.value = cause instanceof ApiError ? cause.message : "Could not load the asset.";
  } finally {
    contentLoading.value = false;
  }
}
</script>

<template>
  <div>
    <div class="d-flex align-center mb-4">
      <h1 class="text-h5">Posted data</h1>
      <v-spacer />
      <span class="text-body-2 text-medium-emphasis">{{ total }}</span>
    </div>
    <p class="text-body-2 text-medium-emphasis mb-4">
      Assets stored in the catalogue database. Select a row to read its body.
    </p>
    <v-alert v-if="error" type="warning" variant="tonal" class="mb-4" :text="error" />
    <v-data-table
      :headers="headers"
      :items="assets"
      :loading="loading"
      item-value="id"
      @click:row="(_event: unknown, row: { item: PostedAsset }) => openAsset(row.item)"
    >
      <template #no-data>No assets stored.</template>
    </v-data-table>

    <v-card v-if="contentLoading || selected || contentError" border class="mt-4">
      <v-card-item>
        <v-card-title>Stored body</v-card-title>
        <v-card-subtitle v-if="selected">{{ selected.contentType || "unknown type" }}</v-card-subtitle>
      </v-card-item>
      <v-card-text>
        <v-progress-linear v-if="contentLoading" indeterminate color="primary" class="mb-2" />
        <v-alert v-if="contentError" type="warning" variant="tonal" :text="contentError" />
        <p v-else-if="selected?.binary" class="text-medium-emphasis">
          This asset is not text. The catalogue kept the file, and this view does not render it.
        </p>
        <pre v-else class="text-body-2" style="white-space: pre-wrap; max-height: 28rem; overflow: auto">{{
          selected?.content || "This asset has no stored text."
        }}</pre>
      </v-card-text>
    </v-card>
  </div>
</template>
