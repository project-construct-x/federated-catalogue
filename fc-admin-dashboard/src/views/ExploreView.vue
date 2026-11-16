<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import GraphCanvas from "@/components/GraphCanvas.vue";
import HelpTip from "@/components/HelpTip.vue";
import { loadLobby, loadNeighborhood, loadSearch, loadType, type LoadedScene } from "@/graph/load";
import type { Bubble, Scene } from "@/graph/scene";
import { clip, colorFor, displayTypes, friendly, isNamePredicate, isTypePredicate, prettyPredicate } from "@/graph/terms";
import { api } from "@/services/api";
import { ApiError, type QueryInfo } from "@/types";

interface Crumb {
  key: string;
  label: string;
}

const loading = ref(true);
const error = ref("");
const note = ref("");
const capped = ref(false);
const search = ref("");
const info = ref<QueryInfo | null>(null);
const scene = ref<Scene>({ bubbles: [], strands: [], focusId: null });
const selectedId = ref<string | null>(null);
const trail = ref<Crumb[]>([]);
const copied = ref(false);

const cache = new Map<string, LoadedScene>();
let ticket = 0;
let copyTimer = 0;

const resourceCount = computed(() => scene.value.bubbles.filter((bubble) => bubble.kind === "resource").length);

const canvasNodes = computed(() =>
  scene.value.bubbles.map((bubble) => ({
    id: bubble.id,
    label: bubble.kind === "literal" ? clip(bubble.label, 28) : clip(bubble.label, 22),
    title: bubble.kind === "literal" ? (bubble.facts[0]?.value ?? bubble.label) : bubble.id,
    kind: bubble.kind,
    color: bubble.kind === "literal" ? "#546E7A" : colorFor(bubble.types)
  }))
);

const canvasEdges = computed(() =>
  scene.value.strands.map((strand) => ({
    id: strand.id,
    source: strand.source,
    target: strand.target,
    label: prettyPredicate(strand.predicate),
    literal: strand.target.startsWith("literal:") || strand.source.startsWith("literal:")
  }))
);

const selected = computed(() => scene.value.bubbles.find((bubble) => bubble.id === selectedId.value) ?? null);

const literalOwner = computed(() => {
  if (selected.value?.kind !== "literal") return null;
  const strand = scene.value.strands.find((item) => item.target === selected.value?.id);
  if (!strand) return null;
  return scene.value.bubbles.find((bubble) => bubble.id === strand.source) ?? null;
});

const facts = computed(() =>
  (selected.value?.facts ?? []).filter(
    (fact) => !isNamePredicate(fact.predicate) && !isTypePredicate(fact.predicate)
  )
);

const hops = computed(() => {
  const current = selected.value;
  if (!current || current.kind !== "resource") return [];
  const items = scene.value.strands.flatMap((strand) => {
    if (strand.source.startsWith("literal:") || strand.target.startsWith("literal:")) return [];
    if (strand.source === current.id) {
      return [
        {
          key: strand.id,
          targetId: strand.target,
          relation: `${prettyPredicate(strand.predicate)} →`,
          label: bubbleLabel(strand.target)
        }
      ];
    }
    if (strand.target === current.id) {
      return [
        {
          key: strand.id,
          targetId: strand.source,
          relation: `← ${prettyPredicate(strand.predicate)}`,
          label: bubbleLabel(strand.source)
        }
      ];
    }
    return [];
  });
  return items.sort((a, b) => a.label.localeCompare(b.label) || a.relation.localeCompare(b.relation));
});

const gallery = computed(() =>
  scene.value.focusId == null
    ? scene.value.bubbles.filter((bubble) => bubble.kind === "resource")
    : []
);

onMounted(async () => {
  try {
    info.value = await api<QueryInfo>("/admin/query/info");
  } catch (cause) {
    error.value = textOf(cause, "Could not read the graph backend.");
    loading.value = false;
    return;
  }
  if (!info.value.enabled || !info.value.contentType) {
    error.value = "The graph store is disabled.";
    loading.value = false;
    return;
  }
  await boot();
});

onUnmounted(() => window.clearTimeout(copyTimer));

function bubbleLabel(id: string): string {
  return scene.value.bubbles.find((bubble) => bubble.id === id)?.label ?? friendly(id);
}

function textOf(cause: unknown, fallback: string): string {
  if (cause instanceof ApiError) return cause.message;
  if (cause instanceof Error && cause.message) return cause.message;
  return fallback;
}

async function guard<T>(work: () => Promise<T>): Promise<T | undefined> {
  const mine = ++ticket;
  loading.value = true;
  error.value = "";
  note.value = "";
  try {
    const result = await work();
    if (mine !== ticket) return undefined;
    return result;
  } catch (cause) {
    if (mine !== ticket) return undefined;
    error.value = textOf(cause, "Could not read the graph.");
    return undefined;
  } finally {
    if (mine === ticket) loading.value = false;
  }
}

function apply(loaded: LoadedScene) {
  scene.value = loaded.scene;
  capped.value = loaded.capped;
  selectedId.value = loaded.scene.focusId;
  note.value = "";
}

function push(key: string, label: string) {
  const next = [...trail.value, { key, label: clip(label, 28) }];
  const first = next[0];
  trail.value = next.length > 12 && first ? [first, ...next.slice(-11)] : next;
}

async function boot() {
  if (!info.value) return;
  const loaded = await guard(() => loadLobby(info.value!));
  if (!loaded) return;
  cache.set("lobby", loaded);
  trail.value = [{ key: "lobby", label: "Everything" }];
  apply(loaded);
}

function jump(index: number) {
  const crumb = trail.value[index];
  if (!crumb) return;
  const loaded = cache.get(crumb.key);
  if (!loaded) return;
  trail.value = trail.value.slice(0, index + 1);
  apply(loaded);
}

function back() {
  if (trail.value.length < 2) return;
  jump(trail.value.length - 2);
}

function startOver() {
  search.value = "";
  const loaded = cache.get("lobby");
  if (!loaded) {
    void boot();
    return;
  }
  trail.value = trail.value.slice(0, 1);
  apply(loaded);
}

async function openResource(iri: string) {
  if (!info.value) return;
  if (trail.value.at(-1)?.key === iri) {
    selectedId.value = iri;
    return;
  }
  const known = scene.value.bubbles.find((bubble) => bubble.id === iri);
  const cached = cache.get(iri);
  if (cached) {
    push(iri, cached.scene.bubbles.find((bubble) => bubble.id === iri)?.label ?? known?.label ?? friendly(iri));
    apply(cached);
    return;
  }
  const loaded = await guard(() => loadNeighborhood(info.value!, iri));
  if (!loaded) return;
  cache.set(iri, loaded);
  push(iri, loaded.scene.bubbles.find((bubble) => bubble.id === iri)?.label ?? known?.label ?? friendly(iri));
  apply(loaded);
}

async function runSearch() {
  if (!info.value) return;
  const term = search.value.trim();
  if (term.length < 2) {
    note.value = "Type at least 2 characters.";
    return;
  }
  const loaded = await guard(() => loadSearch(info.value!, term));
  if (!loaded) return;
  if (loaded.scene.bubbles.length === 0) {
    note.value = "Nothing in the graph matches that.";
    return;
  }
  const key = `search:${term.toLowerCase()}`;
  cache.set(key, loaded);
  push(key, `“${clip(term, 24)}”`);
  apply(loaded);
}

async function showType(type: string) {
  if (!info.value) return;
  const key = `type:${type}`;
  if (trail.value.at(-1)?.key === key) return;
  const cached = cache.get(key);
  if (cached) {
    push(key, prettyPredicate(type));
    apply(cached);
    return;
  }
  const loaded = await guard(() => loadType(info.value!, type));
  if (!loaded) return;
  if (loaded.scene.bubbles.length === 0) {
    note.value = "Nothing else uses that type.";
    return;
  }
  cache.set(key, loaded);
  push(key, prettyPredicate(type));
  apply(loaded);
}

function wander() {
  const choices = scene.value.bubbles.filter(
    (bubble) => bubble.kind === "resource" && bubble.id !== scene.value.focusId
  );
  if (choices.length === 0) {
    note.value = "Nothing else is linked here.";
    return;
  }
  const pick = choices[Math.floor(Math.random() * choices.length)];
  if (!pick) return;
  void openResource(pick.id);
}

function onActivate(id: string) {
  const bubble = scene.value.bubbles.find((item) => item.id === id);
  if (!bubble) return;
  if (bubble.kind === "literal") {
    selectedId.value = id;
    note.value = "";
    return;
  }
  void openResource(id);
}

function showOwner(owner: Bubble) {
  selectedId.value = owner.id;
}

async function copyId(value: string) {
  try {
    await navigator.clipboard.writeText(value);
    copied.value = true;
    window.clearTimeout(copyTimer);
    copyTimer = window.setTimeout(() => {
      copied.value = false;
    }, 1500);
  } catch {
    copied.value = false;
  }
}
</script>

<template>
  <div class="explore">
    <div class="head">
      <h1 class="text-h5 mb-1 d-inline-flex align-center">
        Explore the graph
        <HelpTip
          text="Each bubble is something stored in the graph. Click one to bring its claims closer. Lines are links. Drag a bubble to move it, and scroll to zoom."
        />
      </h1>
      <p class="text-body-2 text-medium-emphasis mb-3">
        Click a bubble to walk its links. Search when you already know a name.
        <span v-if="info?.backend"> Backend: {{ info.backend }}{{ info.queryLanguage ? `, ${info.queryLanguage}` : "" }}.</span>
        <span v-if="resourceCount"> {{ resourceCount }} in view.</span>
      </p>

      <div class="d-flex flex-wrap align-center mb-2">
        <v-text-field
          v-model="search"
          class="search mr-2"
          density="compact"
          hide-details
          clearable
          label="Find"
          placeholder="Name or identifier"
          prepend-inner-icon="mdi-magnify"
          :disabled="!info?.enabled"
          @keyup.enter="runSearch"
        />
        <v-btn class="mr-2 mb-2" color="primary" variant="flat" :disabled="!info?.enabled" @click="runSearch">Look</v-btn>
        <v-btn
          class="mr-2 mb-2"
          variant="tonal"
          prepend-icon="mdi-shuffle-variant"
          :disabled="!info?.enabled || resourceCount < 2"
          @click="wander"
        >
          Wander
        </v-btn>
        <v-btn class="mr-2 mb-2" variant="text" :disabled="trail.length < 2" @click="back">Back</v-btn>
        <v-btn class="mb-2" variant="text" :disabled="trail.length < 2" @click="startOver">Start over</v-btn>
      </div>

      <div v-if="trail.length" class="trail mb-2">
        <v-chip
          v-for="(crumb, index) in trail"
          :key="`${crumb.key}-${index}`"
          size="small"
          class="mr-1 mb-1"
          :color="index === trail.length - 1 ? 'primary' : undefined"
          :variant="index === trail.length - 1 ? 'flat' : 'outlined'"
          @click="jump(index)"
        >
          {{ crumb.label }}
        </v-chip>
      </div>
      <p v-if="note" class="text-body-2 mb-2">{{ note }}</p>
      <p v-if="capped" class="text-body-2 text-medium-emphasis mb-2">
        Showing a sample. Open a bubble, or search, to look closer.
      </p>
      <v-alert v-if="error" type="warning" variant="tonal" class="mb-2" :text="error" />
    </div>

    <div class="stage">
      <div class="map">
        <v-progress-linear v-if="loading" indeterminate color="primary" class="load-bar" />
        <GraphCanvas
          :nodes="canvasNodes"
          :edges="canvasEdges"
          :focus-id="scene.focusId"
          :selected-id="selectedId"
          @activate="onActivate"
        >
          <template #empty>
            <p v-if="!error" class="text-body-2">
              The graph has no claims yet. Post a credential and the bubbles show up here.
            </p>
          </template>
        </GraphCanvas>
      </div>

      <v-card class="detail" border>
        <v-card-text>
          <template v-if="selected?.kind === 'literal'">
            <div class="text-caption text-medium-emphasis mb-1">Value</div>
            <h2 class="text-h6 mb-2">{{ selected.facts[0]?.value || selected.label }}</h2>
            <div class="text-caption text-medium-emphasis">
              {{ prettyPredicate(selected.facts[0]?.predicate || "") }}
            </div>
            <v-btn
              v-if="literalOwner"
              class="mt-4"
              variant="tonal"
              size="small"
              @click="showOwner(literalOwner)"
            >
              Back to {{ literalOwner.label }}
            </v-btn>
          </template>

          <template v-else-if="selected">
            <div class="text-caption text-medium-emphasis mb-1">Looking at</div>
            <h2 class="text-h6 mb-2">{{ selected.label }}</h2>
            <p class="iri mb-2">{{ selected.id }}</p>
            <v-btn size="small" variant="text" class="px-0 mb-3" @click="copyId(selected.id)">
              {{ copied ? "Copied" : "Copy identifier" }}
            </v-btn>
            <div v-if="displayTypes(selected.types).length" class="mb-3">
              <v-chip
                v-for="type in displayTypes(selected.types)"
                :key="type"
                size="small"
                class="mr-1 mb-1"
                variant="tonal"
                title="Show others of this type"
                @click="showType(type)"
              >
                {{ prettyPredicate(type) }}
              </v-chip>
            </div>
            <div v-for="fact in facts" :key="`${fact.predicate}:${fact.value}`" class="fact">
              <div class="text-caption text-medium-emphasis">{{ prettyPredicate(fact.predicate) }}</div>
              <div class="text-body-2 mb-2">{{ fact.value }}</div>
            </div>
            <div v-if="hops.length" class="text-caption text-medium-emphasis mt-2 mb-1">Links</div>
            <button v-for="hop in hops" :key="hop.key" type="button" class="hop" @click="openResource(hop.targetId)">
              <span class="hop-rel">{{ hop.relation }}</span>
              <span>{{ hop.label }}</span>
            </button>
            <p v-if="facts.length === 0 && hops.length === 0" class="text-body-2 text-medium-emphasis">
              No further claims are attached.
            </p>
          </template>

          <template v-else>
            <div class="text-caption text-medium-emphasis mb-1">In view</div>
            <h2 class="text-h6 mb-2">Pick a bubble</h2>
            <p class="text-body-2 text-medium-emphasis mb-3">
              Each one is something the catalogue stored. Click it to step closer.
            </p>
            <button
              v-for="bubble in gallery"
              :key="bubble.id"
              type="button"
              class="hop"
              @click="openResource(bubble.id)"
            >
              <span>{{ bubble.label }}</span>
              <span v-if="displayTypes(bubble.types).length" class="hop-rel">
                {{ prettyPredicate(displayTypes(bubble.types)[0] || "") }}
              </span>
            </button>
            <p v-if="gallery.length === 0" class="text-body-2 text-medium-emphasis">Nothing is in view yet.</p>
          </template>
        </v-card-text>
      </v-card>
    </div>
  </div>
</template>

<style scoped>
.explore {
  height: calc(100vh - 104px);
  min-height: 560px;
  display: flex;
  flex-direction: column;
}

.head {
  flex: 0 0 auto;
}

.stage {
  flex: 1 1 auto;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 12px;
}

.map {
  position: relative;
  min-width: 0;
  min-height: 0;
  height: 100%;
}

.load-bar {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  z-index: 2;
}

.detail {
  min-height: 0;
  overflow: auto;
}

.search {
  flex: 1 1 220px;
  max-width: 320px;
}

.iri {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.75rem;
  word-break: break-all;
  color: rgba(0, 0, 0, 0.62);
}

.hop {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  width: 100%;
  text-align: left;
  border: 1px solid rgba(21, 101, 192, 0.16);
  background: #f7fbff;
  border-radius: 12px;
  padding: 8px 10px;
  margin-bottom: 8px;
  cursor: pointer;
}

.hop:hover,
.hop:focus-visible {
  border-color: #1565c0;
  outline: none;
}

.hop-rel {
  font-size: 0.75rem;
  color: rgba(0, 0, 0, 0.6);
}

@media (max-width: 960px) {
  .explore {
    height: auto;
    min-height: 0;
  }

  .stage {
    display: block;
  }

  .map {
    height: 70vh;
    min-height: 420px;
  }

  .map :deep(.pond) {
    min-height: 420px;
  }

  .detail {
    margin-top: 12px;
  }
}
</style>
