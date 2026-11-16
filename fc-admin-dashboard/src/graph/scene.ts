import { friendly, isIri, isNamePredicate, isTypePredicate, usableId } from "./terms";

export interface Fact {
  predicate: string;
  value: string;
}

export interface Bubble {
  id: string;
  label: string;
  kind: "resource" | "literal";
  types: string[];
  facts: Fact[];
}

export interface Strand {
  id: string;
  source: string;
  target: string;
  predicate: string;
}

export interface Scene {
  bubbles: Bubble[];
  strands: Strand[];
  focusId: string | null;
}

export interface Triple {
  subject: string;
  predicate: string;
  object: string;
}

const MAX_RESOURCES = 40;
const MAX_SATELLITES = 8;

export function triplesToScene(triples: Triple[], focusId: string | null, ensureIds: string[] = []): Scene {
  const resources = new Map<string, Bubble>();

  const resource = (id: string): Bubble | null => {
    if (!usableId(id)) return null;
    let bubble = resources.get(id);
    if (!bubble) {
      bubble = { id, label: friendly(id), kind: "resource", types: [], facts: [] };
      resources.set(id, bubble);
    }
    return bubble;
  };

  for (const id of ensureIds) resource(id);
  if (focusId) resource(focusId);

  const strandKeys = new Set<string>();
  const strands: Strand[] = [];

  for (const triple of triples) {
    const subject = resource(triple.subject);
    if (!subject || !triple.predicate || !triple.object || triple.object.startsWith("<<")) continue;

    if (isTypePredicate(triple.predicate)) {
      if (!subject.types.includes(triple.object)) subject.types.push(triple.object);
      continue;
    }

    if (isNamePredicate(triple.predicate) && !isIri(triple.object)) {
      addFact(subject, triple.predicate, triple.object);
      if (subject.label === friendly(subject.id)) subject.label = clipLabel(triple.object);
      continue;
    }

    if (triple.object === triple.subject) {
      addFact(subject, triple.predicate, triple.object);
      continue;
    }

    if (isIri(triple.object)) {
      const object = resource(triple.object);
      if (!object) continue;
      const key = `${subject.id}\n${triple.predicate}\n${object.id}`;
      if (!strandKeys.has(key)) {
        strandKeys.add(key);
        strands.push({ id: key, source: subject.id, target: object.id, predicate: triple.predicate });
      }
      continue;
    }

    addFact(subject, triple.predicate, triple.object);
  }

  const kept = capResources(resources, focusId);
  const visible = strands.filter((strand) => kept.has(strand.source) && kept.has(strand.target));
  const bubbles = [...kept.values()];

  if (focusId && kept.has(focusId)) {
    const focus = kept.get(focusId)!;
    const satellites = focus.facts
      .filter((fact) => !isNamePredicate(fact.predicate) && !isTypePredicate(fact.predicate))
      .filter((fact) => fact.value.length > 0 && fact.value.length <= 40)
      .sort((a, b) => a.value.length - b.value.length)
      .slice(0, MAX_SATELLITES);
    for (const fact of satellites) {
      const id = `literal:${focusId}:${fact.predicate}:${fact.value}`;
      bubbles.push({
        id,
        label: clipLabel(fact.value, 32),
        kind: "literal",
        types: [],
        facts: [fact]
      });
      visible.push({
        id: `edge:${id}`,
        source: focusId,
        target: id,
        predicate: fact.predicate
      });
    }
  }

  bubbles.sort((a, b) => compareBubbles(a, b, focusId));

  return { bubbles, strands: visible, focusId: focusId && kept.has(focusId) ? focusId : null };
}

function capResources(resources: Map<string, Bubble>, focusId: string | null): Map<string, Bubble> {
  if (resources.size <= MAX_RESOURCES) return resources;
  const rest = [...resources.values()].filter((bubble) => bubble.id !== focusId);
  rest.sort((a, b) => score(b) - score(a) || a.label.localeCompare(b.label));
  const kept = new Map<string, Bubble>();
  if (focusId && resources.has(focusId)) kept.set(focusId, resources.get(focusId)!);
  for (const bubble of rest) {
    if (kept.size >= MAX_RESOURCES) break;
    kept.set(bubble.id, bubble);
  }
  return kept;
}

function score(bubble: Bubble): number {
  let value = 0;
  if (bubble.label !== friendly(bubble.id)) value += 2;
  if (bubble.types.length > 0) value += 1;
  return value;
}

function compareBubbles(a: Bubble, b: Bubble, focusId: string | null): number {
  if (a.id === focusId) return -1;
  if (b.id === focusId) return 1;
  if (a.kind !== b.kind) return a.kind === "resource" ? -1 : 1;
  return a.label.localeCompare(b.label);
}

function addFact(bubble: Bubble, predicate: string, value: string) {
  const clean = value.replace(/\s+/g, " ").trim();
  if (!clean) return;
  if (bubble.facts.some((fact) => fact.predicate === predicate && fact.value === clean)) return;
  bubble.facts.push({ predicate, value: clean });
}

function clipLabel(value: string, max = 48): string {
  const clean = value.replace(/\s+/g, " ").trim();
  if (clean.length <= max) return clean;
  return `${clean.slice(0, max - 1)}…`;
}
