<script setup lang="ts">
import { onMounted, ref } from "vue";
import HelpTip from "@/components/HelpTip.vue";
import { api } from "@/services/api";
import { ApiError, type UserPage, type UserProfile } from "@/types";

const loading = ref(true);
const error = ref("");
const users = ref<UserProfile[]>([]);
const total = ref(0);

const headers = [
  { title: "Username", key: "username", hint: "Sign-in name from the identity provider." },
  { title: "Name", key: "name", hint: "Given and family name, when the identity provider has them." },
  { title: "Email", key: "email", hint: "Address recorded on the account." },
  { title: "Roles", key: "roles", hint: "Application roles. Admin is what unlocks catalogue inspection in this dashboard." }
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
      <h1 class="text-h5 d-inline-flex align-center">
        Users
        <HelpTip text="Accounts from the identity provider. This is who can sign in, which is separate from participants published as catalogue credentials." />
      </h1>
      <v-spacer />
      <span class="text-body-2 text-medium-emphasis">{{ total }}</span>
    </div>
    <v-alert v-if="error" type="warning" variant="tonal" class="mb-4" :text="error" />
    <v-data-table :headers="headers" :items="users" :loading="loading" item-value="id">
      <template v-for="header in headers" :key="header.key" #[`header.${header.key}`]="{ column, getSortIcon }">
        <div class="v-data-table-header__content">
          <span class="d-inline-flex align-center">
            {{ column.title }}
            <HelpTip :text="header.hint" />
          </span>
          <v-icon v-if="column.sortable !== false" :icon="getSortIcon(column)" class="v-data-table-header__sort-icon" />
        </div>
      </template>
      <template #item.name="{ item }">{{ name(item) || "—" }}</template>
      <template #item.roles="{ item }">{{ item.roleIds?.join(", ") || "—" }}</template>
      <template #no-data>
        <div class="pa-4 text-body-2">
          <template v-if="error">Could not list users.</template>
          <template v-else>No accounts were returned. The identity provider may have none this session can list.</template>
        </div>
      </template>
    </v-data-table>
  </div>
</template>
