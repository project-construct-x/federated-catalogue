<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api } from "@/services/api";
import { ApiError, type AdminHealth, type AdminStats, type GraphStatus, type SessionInfo } from "@/types";

const loading = ref(true);
const error = ref("");
const stats = ref<AdminStats | null>(null);
const health = ref<AdminHealth | null>(null);
const graph = ref<GraphStatus | null>(null);
const session = ref<SessionInfo | null>(null);

const cards = computed(() => {
  const current = stats.value;
  const text = (value?: number) => (value == null ? "—" : String(value));
  return [
    { label: "Assets", value: text(current?.totalAssets), hint: current?.activeAssets == null ? "" : `${current.activeAssets} active` },
    { label: "Participants", value: text(current?.totalParticipants), hint: "" },
    { label: "Users", value: text(current?.totalUsers), hint: "" },
    { label: "Schemas", value: text(current?.totalSchemas), hint: "" },
    { label: "Trust frameworks", value: text(current?.activeTrustFrameworks), hint: "" },
    { label: "Graph claims", value: text(current?.graphClaimCount), hint: current?.graphBackend || "" }
  ];
});

onMounted(async () => {
  try {
    await api("/admin/me");
    const [statsResult, healthResult, graphResult, sessionResult] = await Promise.allSettled([
      api<AdminStats>("/admin/stats"),
      api<AdminHealth>("/admin/health"),
      api<GraphStatus>("/admin/graph/status"),
      api<SessionInfo>("/session")
    ]);
    if (statsResult.status === "fulfilled") stats.value = statsResult.value;
    if (healthResult.status === "fulfilled") health.value = healthResult.value;
    if (graphResult.status === "fulfilled") graph.value = graphResult.value;
    if (sessionResult.status === "fulfilled") session.value = sessionResult.value;
    const failed = [statsResult, healthResult, graphResult, sessionResult].find(
      (result) => result.status === "rejected"
    );
    if (failed?.status === "rejected") {
      error.value = failed.reason instanceof Error ? failed.reason.message : "Could not load the instance.";
    }
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : "Could not load the instance.";
  } finally {
    loading.value = false;
  }
});

function chipColor(value?: string | boolean): string {
  const text = String(value ?? "").toUpperCase();
  if (value === true || text === "UP" || text === "IN-SYNC") return "success";
  if (value === false || text === "DOWN" || text === "OUT-OF-SYNC" || text === "FAILED") return "error";
  return "warning";
}
</script>

<template>
  <div>
    <h1 class="text-h5 mb-4">Instance</h1>
    <v-progress-linear v-if="loading" indeterminate color="primary" class="mb-4" />
    <v-alert v-if="error" type="warning" variant="tonal" class="mb-4" :text="error" />

    <v-row>
      <v-col v-for="card in cards" :key="card.label" cols="12" sm="6" md="4">
        <v-card border>
          <v-card-item>
            <v-card-subtitle>{{ card.label }}</v-card-subtitle>
            <v-card-title class="text-h4">{{ card.value }}</v-card-title>
          </v-card-item>
          <v-card-text v-if="card.hint" class="text-medium-emphasis pt-0">{{ card.hint }}</v-card-text>
        </v-card>
      </v-col>
    </v-row>

    <v-row class="mt-2">
      <v-col cols="12" md="6">
        <v-card border title="Health">
          <v-list v-if="health" density="compact">
            <v-list-item title="Catalogue">
              <template #append>
                <v-chip size="small" :color="chipColor(health.catalogueStatus)">{{ health.catalogueStatus || "—" }}</v-chip>
              </template>
            </v-list-item>
            <v-list-item title="Database">
              <template #append>
                <v-chip size="small" :color="chipColor(health.databaseStatus)">{{ health.databaseStatus || "—" }}</v-chip>
              </template>
            </v-list-item>
            <v-list-item title="Keycloak">
              <template #append>
                <v-chip size="small" :color="chipColor(health.keycloakStatus)">{{ health.keycloakStatus || "—" }}</v-chip>
              </template>
            </v-list-item>
            <v-list-item title="File store">
              <template #append>
                <v-chip size="small" :color="chipColor(health.fileStoreStatus)">{{ health.fileStoreStatus || "—" }}</v-chip>
              </template>
            </v-list-item>
            <v-list-item title="Graph database" :subtitle="health.graphDbStatus?.backend">
              <template #append>
                <v-chip size="small" :color="chipColor(health.graphDbStatus?.connected)">
                  {{ health.graphDbStatus?.connected ? "Connected" : "Down" }}
                </v-chip>
              </template>
            </v-list-item>
          </v-list>
          <v-card-text v-else class="text-medium-emphasis">No health report yet.</v-card-text>
        </v-card>
      </v-col>

      <v-col cols="12" md="6">
        <v-card border title="Graph" class="mb-4">
          <v-list v-if="graph" density="compact">
            <v-list-item title="Backend" :subtitle="graph.backend || '—'" />
            <v-list-item title="Sync" :subtitle="graph.syncAssessment || '—'" />
            <v-list-item title="Active assets" :subtitle="String(graph.activeAssetCount ?? '—')" />
            <v-list-item title="Assets in graph" :subtitle="String(graph.assetCountInGraph ?? '—')" />
            <v-list-item title="Claims in graph" :subtitle="String(graph.claimCountInGraph ?? '—')" />
          </v-list>
          <v-card-text v-else class="text-medium-emphasis">No graph status yet.</v-card-text>
        </v-card>

        <v-card border title="Session">
          <v-list v-if="session" density="compact">
            <v-list-item title="User" :subtitle="session.userId || '—'" />
            <v-list-item title="Status" :subtitle="session.status || '—'" />
            <v-list-item title="Roles" :subtitle="session.roleIds?.join(', ') || '—'" />
          </v-list>
          <v-card-text v-else class="text-medium-emphasis">No session yet.</v-card-text>
        </v-card>
      </v-col>
    </v-row>
  </div>
</template>
