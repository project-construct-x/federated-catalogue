<script setup lang="ts">
import { computed } from "vue";
import { useRoute } from "vue-router";
import { useAuthStore } from "@/stores/auth";

const route = useRoute();
const auth = useAuthStore();
const error = computed(() => (typeof route.query.error === "string" ? route.query.error : ""));
</script>

<template>
  <v-main class="d-flex align-center justify-center h-screen">
    <v-card border width="420" max-width="92vw">
      <v-card-item>
        <v-card-title>Catalogue admin</v-card-title>
        <v-card-subtitle>Sign in with Keycloak</v-card-subtitle>
      </v-card-item>
      <v-card-text>
        <v-alert v-if="error" type="error" variant="tonal" class="mb-4" :text="error" />
        <p class="text-body-2 mb-4">
          The account needs the <strong>federated-catalogue</strong> client role
          <strong>ADMIN_ALL</strong> to read this instance.
        </p>
        <v-btn color="primary" block size="large" prepend-icon="mdi-login" @click="auth.login()">
          Sign in
        </v-btn>
      </v-card-text>
    </v-card>
  </v-main>
</template>
