<script setup lang="ts">
import { useAuthStore } from "@/stores/auth";

const auth = useAuthStore();

const items = [
  { title: "Overview", to: "/", icon: "mdi-view-dashboard-outline" },
  { title: "Users", to: "/users", icon: "mdi-account-multiple-outline" },
  { title: "Schemas", to: "/schemas", icon: "mdi-file-code-outline" },
  { title: "Trust frameworks", to: "/frameworks", icon: "mdi-shield-check-outline" },
  { title: "Posted data", to: "/posted", icon: "mdi-database-outline" },
  { title: "Graph query", to: "/query", icon: "mdi-graphql" }
];
</script>

<template>
  <v-app-bar flat border>
    <v-app-bar-title>Federated Catalogue</v-app-bar-title>
    <v-spacer />
    <v-chip size="small" variant="tonal" :color="auth.isAdmin ? 'primary' : undefined" class="mr-3">
      {{ auth.isAdmin ? "Admin" : "Signed in" }}
    </v-chip>
    <span class="text-body-2 mr-2">{{ auth.displayName }}</span>
    <v-btn variant="text" prepend-icon="mdi-logout" @click="auth.logout()">Sign out</v-btn>
  </v-app-bar>

  <v-navigation-drawer permanent width="228">
    <v-list nav density="compact">
      <v-list-item
        v-for="item in items"
        :key="item.to"
        :to="item.to"
        :title="item.title"
        :prepend-icon="item.icon"
      />
    </v-list>
  </v-navigation-drawer>

  <v-main>
    <v-container class="py-6">
      <router-view />
    </v-container>
  </v-main>
</template>
