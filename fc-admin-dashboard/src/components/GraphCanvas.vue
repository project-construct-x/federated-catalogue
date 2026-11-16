<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { hash } from "@/graph/terms";

interface CanvasNode {
  id: string;
  label: string;
  title: string;
  kind: "resource" | "literal";
  color: string;
}

interface CanvasEdge {
  id: string;
  source: string;
  target: string;
  label: string;
  literal: boolean;
}

interface Body {
  id: string;
  x: number;
  y: number;
  vx: number;
  vy: number;
  drawX: number;
  drawY: number;
}

interface DragNode {
  kind: "node";
  id: string;
  pointer: number;
  startX: number;
  startY: number;
  offsetX: number;
  offsetY: number;
  moved: boolean;
}

interface DragPan {
  kind: "pan";
  pointer: number;
  x: number;
  y: number;
}

const props = defineProps<{
  nodes: CanvasNode[];
  edges: CanvasEdge[];
  focusId: string | null;
  selectedId: string | null;
}>();

const emit = defineEmits<{
  activate: [id: string];
}>();

const svg = ref<SVGSVGElement | null>(null);
const ready = ref(false);
const bodies = ref<Body[]>([]);
const camera = reactive({ x: 0, y: 0, zoom: 1 });

const reduceMotion =
  typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

let width = 0;
let height = 0;
let userMoved = false;
let frame = 0;
let fitTimer = 0;
let lastTick = 0;
let lastActivate = 0;
let drag: DragNode | DragPan | null = null;
let observer: ResizeObserver | null = null;

const signature = computed(() => `${props.focusId ?? ""}|${props.nodes.map((node) => node.id).join("\n")}`);

const ordered = computed(() =>
  [...props.nodes].sort((a, b) => {
    if (a.id === props.focusId) return 1;
    if (b.id === props.focusId) return -1;
    return 0;
  })
);

const visuals = computed(() =>
  ordered.value.flatMap((node) => {
    const body = bodies.value.find((item) => item.id === node.id);
    return body ? [{ node, body }] : [];
  })
);

const drawn = computed(() => {
  const map = new Map(bodies.value.map((body) => [body.id, body]));
  const seen = new Map<string, number>();
  return props.edges.flatMap((edge) => {
    const source = map.get(edge.source);
    const target = map.get(edge.target);
    if (!source || !target) return [];
    const pair = [edge.source, edge.target].sort().join("|");
    const index = seen.get(pair) ?? 0;
    seen.set(pair, index + 1);
    const bend = (index % 2 === 0 ? 1 : -1) * (18 + Math.floor(index / 2) * 14);
    const dx = target.drawX - source.drawX;
    const dy = target.drawY - source.drawY;
    const dist = Math.hypot(dx, dy) || 1;
    const sourceNode = props.nodes.find((node) => node.id === edge.source);
    const targetNode = props.nodes.find((node) => node.id === edge.target);
    const sx = source.drawX + (dx / dist) * inset(sourceNode, dist);
    const sy = source.drawY + (dy / dist) * inset(sourceNode, dist);
    const tx = target.drawX - (dx / dist) * inset(targetNode, dist);
    const ty = target.drawY - (dy / dist) * inset(targetNode, dist);
    const mx = (sx + tx) / 2;
    const my = (sy + ty) / 2;
    const cx = mx + (-dy / dist) * bend;
    const cy = my + (dx / dist) * bend;
    return [
      {
        id: edge.id,
        d: `M ${sx} ${sy} Q ${cx} ${cy} ${tx} ${ty}`,
        label: edge.label,
        x: 0.25 * sx + 0.5 * cx + 0.25 * tx,
        y: 0.25 * sy + 0.5 * cy + 0.25 * ty,
        showLabel:
          !edge.literal &&
          props.focusId != null &&
          (edge.source === props.focusId || edge.target === props.focusId),
        literal: edge.literal
      }
    ];
  });
});

watch(
  signature,
  () => {
    userMoved = false;
    layoutFresh();
    fit();
    scheduleFit();
    if (reduceMotion) settle();
  },
  { immediate: true }
);

onMounted(async () => {
  ready.value = true;
  await nextTick();
  measure();
  fit();
  const root = svg.value;
  if (!root) return;
  observer = new ResizeObserver(() => {
    measure();
    if (!userMoved) fit();
  });
  observer.observe(root);
  root.addEventListener("wheel", onWheel, { passive: false });
  if (reduceMotion) settle();
  else frame = requestAnimationFrame(tick);
});

onUnmounted(() => {
  cancelAnimationFrame(frame);
  window.clearTimeout(fitTimer);
  observer?.disconnect();
  svg.value?.removeEventListener("wheel", onWheel);
});

function layoutFresh() {
  const focus = props.nodes.find((node) => node.id === props.focusId);
  const resources = props.nodes.filter((node) => node.kind === "resource" && node.id !== props.focusId);
  const literals = props.nodes.filter((node) => node.kind === "literal");
  const next: Body[] = [];
  if (focus) next.push(bodyAt(focus.id, 0, 0));
  const resourceRing = Math.max(150, Math.min(250, 130 + resources.length * 16));
  resources.forEach((node, index) => {
    const angle = (index / Math.max(resources.length, 1)) * Math.PI * 2 - Math.PI / 2;
    next.push(bodyAt(node.id, Math.cos(angle) * resourceRing, Math.sin(angle) * resourceRing));
  });
  literals.forEach((node, index) => {
    const angle = (index / Math.max(literals.length, 1)) * Math.PI * 2 - Math.PI / 4;
    next.push(bodyAt(node.id, Math.cos(angle) * 108, Math.sin(angle) * 108));
  });
  bodies.value = next;
}

function bodyAt(id: string, x: number, y: number): Body {
  return { id, x, y, vx: 0, vy: 0, drawX: x, drawY: y };
}

function measure() {
  const rect = svg.value?.getBoundingClientRect();
  width = rect?.width ?? 0;
  height = rect?.height ?? 0;
}

function fit() {
  measure();
  if (width < 40 || height < 40) return;
  const list = bodies.value;
  if (list.length === 0) {
    camera.x = width / 2;
    camera.y = height / 2;
    camera.zoom = 1;
    return;
  }
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (const body of list) {
    minX = Math.min(minX, body.x);
    minY = Math.min(minY, body.y);
    maxX = Math.max(maxX, body.x);
    maxY = Math.max(maxY, body.y);
  }
  maxY += 28;
  const worldW = Math.max(maxX - minX + 120, 140);
  const worldH = Math.max(maxY - minY + 150, 140);
  camera.zoom = Math.min(1.85, Math.max(0.45, Math.min((width - 56) / worldW, (height - 56) / worldH)));
  camera.x = width / 2 - ((minX + maxX) / 2) * camera.zoom;
  camera.y = height / 2 - ((minY + maxY) / 2) * camera.zoom;
}

function scheduleFit() {
  window.clearTimeout(fitTimer);
  fitTimer = window.setTimeout(() => {
    if (!userMoved) fit();
  }, 480);
}

function settle() {
  for (let i = 0; i < 160; i += 1) step(1);
  paint(0);
  bodies.value = [...bodies.value];
}

function tick(now: number) {
  const dt = lastTick === 0 ? 1 : Math.min(2, (now - lastTick) / 16.67);
  lastTick = now;
  step(dt);
  paint(now);
  frame = requestAnimationFrame(tick);
}

function step(dt: number) {
  const list = bodies.value;
  const byId = new Map(list.map((body) => [body.id, body]));
  for (let i = 0; i < list.length; i += 1) {
    for (let j = i + 1; j < list.length; j += 1) {
      const a = list[i];
      const b = list[j];
      if (!a || !b) continue;
      let dx = b.x - a.x;
      let dy = b.y - a.y;
      let dist = Math.hypot(dx, dy);
      if (dist < 0.1) {
        dx = 1;
        dy = 0;
        dist = 1;
      }
      if (dist < 240) {
        const force = ((78 * 78) / (dist * dist)) * 0.07 * dt;
        const ux = dx / dist;
        const uy = dy / dist;
        a.vx -= ux * force;
        a.vy -= uy * force;
        b.vx += ux * force;
        b.vy += uy * force;
      }
    }
  }
  for (const edge of props.edges) {
    const a = byId.get(edge.source);
    const b = byId.get(edge.target);
    if (!a || !b) continue;
    const dx = b.x - a.x;
    const dy = b.y - a.y;
    const dist = Math.hypot(dx, dy) || 1;
    const rest = edge.literal ? 112 : 168;
    const force = (dist - rest) * 0.012 * dt;
    a.vx += (dx / dist) * force;
    a.vy += (dy / dist) * force;
    b.vx -= (dx / dist) * force;
    b.vy -= (dy / dist) * force;
  }
  const dragged = drag?.kind === "node" ? drag.id : null;
  for (const body of list) {
    if (body.id === dragged) {
      body.vx = 0;
      body.vy = 0;
      continue;
    }
    const pull = body.id === props.focusId ? 0.08 : 0.003;
    body.vx += -body.x * pull * dt;
    body.vy += -body.y * pull * dt;
    body.vx *= 0.72;
    body.vy *= 0.72;
    const speed = Math.hypot(body.vx, body.vy);
    if (speed > 8) {
      body.vx = (body.vx / speed) * 8;
      body.vy = (body.vy / speed) * 8;
    }
    body.x += body.vx * dt;
    body.y += body.vy * dt;
  }
}

function paint(now: number) {
  for (const body of bodies.value) {
    const phase = (hash(body.id) % 628) / 100;
    const bob =
      reduceMotion || (drag?.kind === "node" && drag.id === body.id) ? 0 : Math.sin(now / 700 + phase) * 3.5;
    body.drawX = body.x;
    body.drawY = body.y + bob;
  }
}

function inset(node: CanvasNode | undefined, dist: number): number {
  if (!node) return 20;
  const radius = node.kind === "literal" ? 18 : node.id === props.focusId ? 36 : 28;
  return Math.min(radius, dist * 0.42);
}

function radius(node: CanvasNode): number {
  return node.id === props.focusId ? 34 : 26;
}

function pillWidth(label: string): number {
  return Math.max(76, Math.min(176, Math.round(label.length * 7.1 + 28)));
}

function zoomBy(factor: number) {
  const cx = width / 2;
  const cy = height / 2;
  const wx = (cx - camera.x) / camera.zoom;
  const wy = (cy - camera.y) / camera.zoom;
  camera.zoom = Math.min(2.4, Math.max(0.35, camera.zoom * factor));
  camera.x = cx - wx * camera.zoom;
  camera.y = cy - wy * camera.zoom;
  userMoved = true;
}

function recenter() {
  userMoved = false;
  fit();
}

function onWheel(event: WheelEvent) {
  event.preventDefault();
  const root = svg.value;
  if (!root) return;
  const rect = root.getBoundingClientRect();
  const mx = event.clientX - rect.left;
  const my = event.clientY - rect.top;
  const wx = (mx - camera.x) / camera.zoom;
  const wy = (my - camera.y) / camera.zoom;
  camera.zoom = Math.min(2.4, Math.max(0.35, camera.zoom * (event.deltaY < 0 ? 1.08 : 0.92)));
  camera.x = mx - wx * camera.zoom;
  camera.y = my - wy * camera.zoom;
  userMoved = true;
}

function world(event: PointerEvent): { x: number; y: number } {
  const rect = svg.value?.getBoundingClientRect();
  const left = rect?.left ?? 0;
  const top = rect?.top ?? 0;
  return {
    x: (event.clientX - left - camera.x) / camera.zoom,
    y: (event.clientY - top - camera.y) / camera.zoom
  };
}

function onPanDown(event: PointerEvent) {
  if (event.button !== 0) return;
  svg.value?.setPointerCapture(event.pointerId);
  drag = { kind: "pan", pointer: event.pointerId, x: event.clientX, y: event.clientY };
}

function onNodeDown(event: PointerEvent, id: string) {
  const body = bodies.value.find((item) => item.id === id);
  if (!body || event.button !== 0) return;
  svg.value?.setPointerCapture(event.pointerId);
  const point = world(event);
  drag = {
    kind: "node",
    id,
    pointer: event.pointerId,
    startX: event.clientX,
    startY: event.clientY,
    offsetX: point.x - body.x,
    offsetY: point.y - body.y,
    moved: false
  };
}

function onMove(event: PointerEvent) {
  const current = drag;
  if (!current || current.pointer !== event.pointerId) return;
  if (current.kind === "pan") {
    camera.x += event.clientX - current.x;
    camera.y += event.clientY - current.y;
    current.x = event.clientX;
    current.y = event.clientY;
    userMoved = true;
    return;
  }
  if (Math.hypot(event.clientX - current.startX, event.clientY - current.startY) > 4) current.moved = true;
  const body = bodies.value.find((item) => item.id === current.id);
  if (!body) return;
  const point = world(event);
  body.x = point.x - current.offsetX;
  body.y = point.y - current.offsetY;
  body.vx = 0;
  body.vy = 0;
  userMoved = true;
}

function onUp(event: PointerEvent) {
  const current = drag;
  if (!current || current.pointer !== event.pointerId) return;
  drag = null;
  if (current.kind === "node" && !current.moved) activate(current.id);
}

function activate(id: string) {
  const now = performance.now();
  if (now - lastActivate < 350) return;
  lastActivate = now;
  emit("activate", id);
}
</script>

<template>
  <div class="pond">
    <svg
      v-show="ready"
      ref="svg"
      class="field"
      role="group"
      aria-label="Graph map"
      @pointerdown="onPanDown"
      @pointermove="onMove"
      @pointerup="onUp"
      @pointercancel="onUp"
    >
      <defs>
        <filter id="graph-shadow" x="-40%" y="-40%" width="180%" height="180%">
          <feDropShadow dx="0" dy="1.5" stdDeviation="1.6" flood-color="#0d47a1" flood-opacity="0.18" />
        </filter>
        <marker id="graph-arrow" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto">
          <path d="M0,0 L7,3 L0,6 Z" fill="#90A4AE" />
        </marker>
      </defs>
      <rect class="catch" width="100%" height="100%" fill="transparent" />
      <g :transform="`translate(${camera.x} ${camera.y}) scale(${camera.zoom})`">
        <path
          v-for="edge in drawn"
          :key="edge.id"
          :d="edge.d"
          :class="edge.literal ? 'edge edge-literal' : 'edge'"
          marker-end="url(#graph-arrow)"
        >
          <title>{{ edge.label }}</title>
        </path>
        <text
          v-for="edge in drawn.filter((edge) => edge.showLabel)"
          :key="`${edge.id}-label`"
          class="edge-label"
          :x="edge.x"
          :y="edge.y"
          text-anchor="middle"
        >
          {{ edge.label }}
        </text>
        <g
          v-for="item in visuals"
          :key="item.node.id"
          class="node"
          role="button"
          tabindex="0"
          :aria-label="item.node.title"
          :transform="`translate(${item.body.drawX} ${item.body.drawY})`"
          @pointerdown.stop="onNodeDown($event, item.node.id)"
          @keydown.enter.prevent="activate(item.node.id)"
          @keydown.space.prevent="activate(item.node.id)"
        >
          <title>{{ item.node.title }}</title>
          <template v-if="item.node.kind === 'resource'">
            <circle
              :r="radius(item.node)"
              :fill="item.node.color"
              stroke="#ffffff"
              :stroke-width="item.node.id === selectedId ? 4 : 3"
              filter="url(#graph-shadow)"
            />
            <ellipse
              :cx="-radius(item.node) * 0.28"
              :cy="-radius(item.node) * 0.34"
              :rx="radius(item.node) * 0.28"
              :ry="radius(item.node) * 0.16"
              fill="#ffffff"
              opacity="0.5"
            />
            <circle
              v-if="item.node.id === selectedId || item.node.id === focusId"
              class="orbit"
              :r="radius(item.node) + 8"
              fill="none"
              :stroke="item.node.color"
              stroke-width="2"
              stroke-dasharray="3 5"
            />
            <text class="node-label" :y="radius(item.node) + 22" text-anchor="middle">{{ item.node.label }}</text>
          </template>
          <template v-else>
            <rect
              :x="-pillWidth(item.node.label) / 2"
              y="-15"
              :width="pillWidth(item.node.label)"
              height="30"
              rx="15"
              fill="#546E7A"
              :stroke="item.node.id === selectedId ? '#1565C0' : '#ffffff'"
              :stroke-width="item.node.id === selectedId ? 3 : 2"
              filter="url(#graph-shadow)"
            />
            <text class="pill-label" text-anchor="middle" dominant-baseline="central">{{ item.node.label }}</text>
          </template>
        </g>
      </g>
    </svg>
    <div v-if="ready && nodes.length === 0" class="empty">
      <slot name="empty" />
    </div>
    <div class="tools" @pointerdown.stop>
      <v-btn class="tool" icon="mdi-plus" size="small" variant="flat" aria-label="Zoom in" @click="zoomBy(1.12)" />
      <v-btn class="tool" icon="mdi-minus" size="small" variant="flat" aria-label="Zoom out" @click="zoomBy(0.89)" />
      <v-btn
        class="tool"
        icon="mdi-arrow-expand-all"
        size="small"
        variant="flat"
        aria-label="Fit the map"
        @click="recenter"
      />
    </div>
  </div>
</template>

<style scoped>
.pond {
  position: relative;
  height: 100%;
  min-height: 0;
  border-radius: 18px;
  overflow: hidden;
  border: 1px solid rgba(21, 101, 192, 0.16);
  background-color: #f3f7fb;
  background-image:
    radial-gradient(circle at 14% 16%, rgba(21, 101, 192, 0.16), transparent 28%),
    radial-gradient(circle at 86% 78%, rgba(0, 137, 123, 0.16), transparent 26%),
    radial-gradient(rgba(21, 101, 192, 0.2) 1px, transparent 1px);
  background-size: auto, auto, 22px 22px;
  user-select: none;
}

.field {
  width: 100%;
  height: 100%;
  display: block;
  touch-action: none;
  cursor: grab;
}

.field:active {
  cursor: grabbing;
}

.node {
  cursor: pointer;
}

.node:focus-visible circle,
.node:focus-visible rect {
  stroke: #0d47a1;
  stroke-width: 4px;
}

.node-label {
  font-size: 12px;
  font-weight: 600;
  fill: #263238;
  stroke: #f3f7fb;
  stroke-width: 4px;
  paint-order: stroke;
  pointer-events: none;
}

.pill-label {
  font-size: 12px;
  fill: #ffffff;
  pointer-events: none;
}

.edge {
  fill: none;
  stroke: #90a4ae;
  stroke-width: 1.6;
}

.edge-literal {
  stroke-dasharray: 4 4;
  stroke: #b0bec5;
}

.edge-label {
  font-size: 11px;
  fill: #546e7a;
  stroke: #f3f7fb;
  stroke-width: 4px;
  paint-order: stroke;
  pointer-events: none;
}

.empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  text-align: center;
  pointer-events: none;
}

.tools {
  position: absolute;
  left: 12px;
  bottom: 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.tools :deep(.v-btn) {
  background: #ffffff;
  color: #1565c0;
  border: 1px solid rgba(21, 101, 192, 0.35);
  box-shadow: 0 1px 4px rgba(13, 71, 161, 0.16);
}

@media (prefers-reduced-motion: no-preference) {
  .orbit {
    animation: orbit 9s linear infinite;
  }
}

@keyframes orbit {
  to {
    stroke-dashoffset: -64;
  }
}
</style>
