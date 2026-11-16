<script setup lang="ts">
import { computed } from "vue";
import { useRoute } from "vue-router";
import { useAuthStore } from "@/stores/auth";

const auth = useAuthStore();
const route = useRoute();
const explore = computed(() => route.name === "explore");
const icon = `${import.meta.env.BASE_URL}icon.png`;

const items = [
  { title: "Overview", to: "/", icon: "mdi-view-dashboard-outline", hint: "Counts, health, and whether the graph matches the database." },
  { title: "Users", to: "/users", icon: "mdi-account-multiple-outline", hint: "People who can sign in. Separate from companies published in the catalogue." },
  { title: "Schemas", to: "/schemas", icon: "mdi-file-code-outline", hint: "Ontologies, shapes, and vocabularies used to check credentials." },
  { title: "Trust frameworks", to: "/frameworks", icon: "mdi-shield-check-outline", hint: "Which credential profiles are switched on." },
  { title: "Posted data", to: "/posted", icon: "mdi-database-outline", hint: "Assets kept in the database. Administrators can store a verifiable credential here." },
  { title: "Graph query", to: "/query", icon: "mdi-graphql", hint: "Read-only search of the claims graph, with examples you can run." },
  { title: "Explore", to: "/explore", icon: "mdi-atom-variant", hint: "Click through the claims graph. Bubbles are things, lines are the links between them." }
];
</script>

<template>
  <v-app-bar flat border>
    <v-app-bar-title class="d-flex align-center">
      <v-img :src="icon" alt="" width="28" height="28" class="mr-2 flex-grow-0" />
      Federated Catalogue
    </v-app-bar-title>
    <v-spacer />
    <v-tooltip location="bottom" max-width="260">
      <template #activator="{ props }">
        <v-chip v-bind="props" size="small" variant="tonal" :color="auth.isAdmin ? 'primary' : undefined" class="mr-3">
          {{ auth.isAdmin ? "Admin" : "Signed in" }}
        </v-chip>
      </template>
      <span class="text-body-2">
        {{
          auth.isAdmin
            ? "Application administrator. You can store a verifiable credential, inspect assets, and run graph queries."
            : "Signed in without the administrator role. Catalogue inspection may be refused."
        }}
      </span>
    </v-tooltip>
    <span class="text-body-2 mr-2">{{ auth.displayName }}</span>
    <v-btn variant="text" prepend-icon="mdi-logout" @click="auth.logout()">Sign out</v-btn>
  </v-app-bar>

  <v-navigation-drawer permanent width="228">
    <v-list nav density="compact">
      <v-tooltip v-for="item in items" :key="item.to" location="end" max-width="260">
        <template #activator="{ props }">
          <v-list-item
            v-bind="props"
            :to="item.to"
            :exact="item.to === '/'"
            :title="item.title"
            :prepend-icon="item.icon"
          />
        </template>
        <span class="text-body-2">{{ item.hint }}</span>
      </v-tooltip>
    </v-list>
  </v-navigation-drawer>

  <v-main>
    <v-container :fluid="explore" :class="explore ? 'py-3 px-4' : 'py-6'">
      <router-view />
    </v-container>
  </v-main>
</template>
