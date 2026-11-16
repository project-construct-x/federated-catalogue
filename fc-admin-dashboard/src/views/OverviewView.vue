<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import HelpTip from "@/components/HelpTip.vue";
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
    {
      label: "Assets",
      value: text(current?.totalAssets),
      hint: current?.activeAssets == null ? "" : `${current.activeAssets} active`,
      tip: "Credentials and other files kept in the catalogue database, including ones that are no longer active. The line under the number is how many are still active."
    },
    {
      label: "Participants",
      value: text(current?.totalParticipants),
      hint: "",
      tip: "Distinct issuers seen on stored assets. Zero is normal before the first credential is posted."
    },
    {
      label: "Users",
      value: text(current?.totalUsers),
      hint: "",
      tip: "Accounts this dashboard can list from the identity provider. This is people who can sign in, not companies published in the catalogue."
    },
    {
      label: "Schemas",
      value: text(current?.totalSchemas),
      hint: "",
      tip: "Ontologies, shapes, and vocabularies registered for validation."
    },
    {
      label: "Trust frameworks",
      value: text(current?.activeTrustFrameworks),
      hint: "",
      tip: "Frameworks currently switched on. Disabled frameworks are kept, and they are not included in this count."
    },
    {
      label: "Graph claims",
      value: text(current?.graphClaimCount),
      hint: current?.graphBackend || "",
      tip: "Statements copied into the graph from active assets. Stays at zero until a credential is posted and indexed. The line under the number names the graph engine."
    }
  ];
});

const quietCatalogue = computed(() => {
  const current = stats.value;
  if (!current) return false;
  return (current.totalAssets ?? 0) === 0 && (current.graphClaimCount ?? 0) === 0;
});

const healthRows = computed(() => {
  const current = health.value;
  if (!current) return [];
  return [
    {
      label: "Catalogue",
      tip: "Whether this catalogue process is answering.",
      subtitle: undefined as string | undefined,
      value: current.catalogueStatus || "—",
      state: current.catalogueStatus as string | boolean | undefined
    },
    {
      label: "Database",
      tip: "Postgres, where posted assets and configuration are stored.",
      subtitle: undefined as string | undefined,
      value: current.databaseStatus || "—",
      state: current.databaseStatus as string | boolean | undefined
    },
    {
      label: "Keycloak",
      tip: "Identity provider used to sign in to this dashboard.",
      subtitle: undefined as string | undefined,
      value: current.keycloakStatus || "—",
      state: current.keycloakStatus as string | boolean | undefined
    },
    {
      label: "File store",
      tip: "Where uploaded credential bytes are kept.",
      subtitle: undefined as string | undefined,
      value: current.fileStoreStatus || "—",
      state: current.fileStoreStatus as string | boolean | undefined
    },
    {
      label: "Graph database",
      tip: "Whether the graph engine accepted a connection. Down means queries fail even when the catalogue itself is up.",
      subtitle: current.graphDbStatus?.backend,
      value: current.graphDbStatus?.connected ? "Connected" : "Down",
      state: current.graphDbStatus?.connected
    }
  ];
});

const graphRows = computed(() => {
  const current = graph.value;
  if (!current) return [];
  return [
    {
      label: "Backend",
      value: current.backend || "—",
      tip: "Which engine answers graph queries. Fuseki uses SPARQL. Neo4j uses openCypher."
    },
    {
      label: "Sync",
      value: current.syncAssessment || "—",
      tip: "IN-SYNC means the active assets in the database match the assets found in the graph."
    },
    {
      label: "Active assets",
      value: String(current.activeAssetCount ?? "—"),
      tip: "Assets whose claims should be in the graph."
    },
    {
      label: "Assets in graph",
      value: String(current.assetCountInGraph ?? "—"),
      tip: "Distinct assets the graph currently indexes."
    },
    {
      label: "Claims in graph",
      value: String(current.claimCountInGraph ?? "—"),
      tip: "Individual statements extracted from those assets."
    }
  ];
});

const sessionRows = computed(() => {
  const current = session.value;
  if (!current) return [];
  return [
    {
      label: "User",
      value: current.userId || "—",
      tip: "Subject from your sign-in token."
    },
    {
      label: "Status",
      value: current.status || "—",
      tip: "Whether this browser session is still valid."
    },
    {
      label: "Roles",
      value: current.roleIds?.join(", ") || "—",
      tip: "Roles on this session. Admin is required for catalogue inspection."
    }
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
    <h1 class="text-h5 mb-2 d-inline-flex align-center">
      Instance
      <HelpTip text="A snapshot of this catalogue node: how much is stored, whether dependencies are up, and whether the graph matches the database." />
    </h1>
    <v-progress-linear v-if="loading" indeterminate color="primary" class="mb-4" />
    <v-alert v-if="error" type="warning" variant="tonal" class="mb-4" :text="error" />
    <v-alert v-if="!loading && quietCatalogue" type="info" variant="tonal" class="mb-4">
      No assets or graph claims are stored yet. The figures below still describe this instance.
      Graph query has examples you can run before any credential is posted.
      <div class="mt-3">
        <v-btn to="/explore" size="small" variant="tonal" color="primary" class="mr-2">Explore the graph</v-btn>
        <v-btn to="/query" size="small" variant="text" color="primary">Open graph query</v-btn>
      </div>
    </v-alert>

    <v-row>
      <v-col v-for="card in cards" :key="card.label" cols="12" sm="6" md="4">
        <v-card border>
          <v-card-item>
            <v-card-subtitle class="d-inline-flex align-center">
              {{ card.label }}
              <HelpTip :text="card.tip" />
            </v-card-subtitle>
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
            <v-list-item v-for="row in healthRows" :key="row.label" :subtitle="row.subtitle">
              <template #title>
                <span class="d-inline-flex align-center">
                  {{ row.label }}
                  <HelpTip :text="row.tip" />
                </span>
              </template>
              <template #append>
                <v-chip size="small" :color="chipColor(row.state)">{{ row.value }}</v-chip>
              </template>
            </v-list-item>
          </v-list>
          <v-card-text v-else class="text-medium-emphasis">No health report yet.</v-card-text>
        </v-card>
      </v-col>

      <v-col cols="12" md="6">
        <v-card border title="Graph" class="mb-4">
          <v-list v-if="graph" density="compact">
            <v-list-item v-for="row in graphRows" :key="row.label" :subtitle="row.value">
              <template #title>
                <span class="d-inline-flex align-center">
                  {{ row.label }}
                  <HelpTip :text="row.tip" />
                </span>
              </template>
            </v-list-item>
          </v-list>
          <v-card-text v-else class="text-medium-emphasis">No graph status yet.</v-card-text>
          <v-card-actions>
            <v-btn to="/explore" size="small" variant="tonal" color="primary" prepend-icon="mdi-atom-variant">
              Explore the graph
            </v-btn>
          </v-card-actions>
        </v-card>

        <v-card border title="Session">
          <v-list v-if="session" density="compact">
            <v-list-item v-for="row in sessionRows" :key="row.label" :subtitle="row.value">
              <template #title>
                <span class="d-inline-flex align-center">
                  {{ row.label }}
                  <HelpTip :text="row.tip" />
                </span>
              </template>
            </v-list-item>
          </v-list>
          <v-card-text v-else class="text-medium-emphasis">No session yet.</v-card-text>
        </v-card>
      </v-col>
    </v-row>
  </div>
</template>
