<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api } from "@/services/api";
import { ApiError, type UserPage, type UserProfile } from "@/types";

const loading = ref(true);
const error = ref("");
const users = ref<UserProfile[]>([]);
const total = ref(0);

const headers = [
  { title: "Username", key: "username" },
  { title: "Name", key: "name" },
  { title: "Email", key: "email" },
  { title: "Roles", key: "roles" }
];

onMounted(async () => {
  try {
    const page = await api<UserPage>("/users?offset=0&limit=200");
    users.value = page.items ?? [];
    total.value = page.totalCount ?? users.value.length;
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : "Could not load users.";
  } finally {
    loading.value = false;
  }
});

function name(user: UserProfile): string {
  return [user.firstName, user.lastName].filter(Boolean).join(" ");
}
</script>

<template>
  <div>
    <div class="d-flex align-center mb-4">
      <h1 class="text-h5">Users</h1>
      <v-spacer />
      <span class="text-body-2 text-medium-emphasis">{{ total }}</span>
    </div>
    <v-alert v-if="error" type="warning" variant="tonal" class="mb-4" :text="error" />
    <v-data-table :headers="headers" :items="users" :loading="loading" item-value="id">
      <template #item.name="{ item }">{{ name(item) || "—" }}</template>
      <template #item.roles="{ item }">{{ item.roleIds?.join(", ") || "—" }}</template>
      <template #no-data>No users registered.</template>
    </v-data-table>
  </div>
</template>
