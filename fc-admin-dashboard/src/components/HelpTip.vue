<script setup lang="ts">
defineProps<{ text: string }>();
</script>

<template>
  <v-tooltip location="bottom" max-width="300">
    <template #activator="{ props }">
      <v-btn
        v-bind="props"
        icon="mdi-help-circle-outline"
        size="x-small"
        variant="text"
        density="compact"
        class="ml-1"
        :aria-label="text"
        @click.stop
      />
    </template>
    <span class="text-body-2">{{ text }}</span>
  </v-tooltip>
</template>
