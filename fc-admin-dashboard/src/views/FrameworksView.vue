<script setup lang="ts">
import { onMounted, ref } from "vue";
import HelpTip from "@/components/HelpTip.vue";
import { api } from "@/services/api";
import { ApiError, type TrustFramework } from "@/types";

const loading = ref(true);
const error = ref("");
const frameworks = ref<TrustFramework[]>([]);

onMounted(async () => {
  try {
    frameworks.value = await api<TrustFramework[]>("/admin/trust-frameworks");
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : "Could not load trust frameworks.";
  } finally {
    loading.value = false;
  }
});

function classes(framework: TrustFramework): string {
  const names = (framework.bundles ?? []).flatMap((bundle) => Object.keys(bundle.baseClasses ?? {}));
  return names.length ? [...new Set(names)].join(", ") : "—";
}
</script>

<template>
  <div>
    <h1 class="text-h5 mb-2 d-inline-flex align-center">
      Trust frameworks
      <HelpTip text="Profiles that decide which credential types are checked. Enabled means the profile is applied. Each bundle names the base classes it accepts." />
    </h1>
    <p class="text-body-2 text-medium-emphasis mb-4">
      Open a framework to see its bundles. A class name is a credential type the bundle can check.
    </p>
    <v-progress-linear v-if="loading" indeterminate color="primary" class="mb-4" />
    <v-alert v-if="error" type="warning" variant="tonal" class="mb-4" :text="error" />
    <v-alert
      v-else-if="!loading && frameworks.length === 0"
      type="info"
      variant="tonal"
      text="No trust frameworks registered."
    />

    <v-expansion-panels v-else variant="accordion">
      <v-expansion-panel v-for="framework in frameworks" :key="framework.id">
        <v-expansion-panel-title>
          <div class="d-flex align-center flex-grow-1">
            <span class="mr-3">{{ framework.name || framework.id }}</span>
            <v-spacer />
            <v-tooltip location="top" max-width="260">
              <template #activator="{ props }">
                <v-chip
                  v-bind="props"
                  size="small"
                  :color="framework.enabled ? 'success' : undefined"
                  class="mr-4"
                  @click.stop
                >
                  {{ framework.enabled ? "Enabled" : "Disabled" }}
                </v-chip>
              </template>
              <span class="text-body-2">
                {{
                  framework.enabled
                    ? "Applied when credentials are checked."
                    : "Stored on this instance, and not applied."
                }}
              </span>
            </v-tooltip>
          </div>
        </v-expansion-panel-title>
        <v-expansion-panel-text>
          <div class="text-body-2 text-medium-emphasis mb-3">{{ framework.id }}</div>
          <div v-if="framework.bundles?.length" class="text-caption text-medium-emphasis mb-1 d-inline-flex align-center">
            Bundles
            <HelpTip text="A bundle groups the credential classes this framework accepts, such as a legal person or a service offering." />
          </div>
          <v-list v-if="framework.bundles?.length" density="compact">
            <v-list-item
              v-for="bundle in framework.bundles"
              :key="bundle.id"
              :title="bundle.id"
              :subtitle="classes({ bundles: [bundle] })"
            />
          </v-list>
          <p v-else class="text-body-2">No bundles.</p>
        </v-expansion-panel-text>
      </v-expansion-panel>
    </v-expansion-panels>
  </div>
</template>
