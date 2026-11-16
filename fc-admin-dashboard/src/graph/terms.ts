const PALETTE = [
  "#1565C0",
  "#00897B",
  "#6A1B9A",
  "#EF6C00",
  "#2E7D32",
  "#00838F",
  "#AD1457",
  "#4527A0",
  "#0277BD",
  "#6D4C41"
];

const GENERIC_TYPES = new Set(["resource", "class", "namedindividual", "thing"]);

const WRITE_QUERY = /\b(INSERT|DELETE|LOAD|CLEAR|DROP|CREATE|MERGE|DETACH|SET|REMOVE)\b/i;

export const RDF = {
  type: "http://www.w3.org/1999/02/22-rdf-syntax-ns#type",
  name: "https://schema.org/name",
  label: "http://www.w3.org/2000/01/rdf-schema#label",
  title: "http://purl.org/dc/terms/title",
  schemaName: "http://schema.org/name",
  credential: "https://www.w3.org/2018/credentials#credentialSubject"
};

const NAME_TAILS = new Set(["name", "label", "title", "preflabel"]);

export function cell(value: unknown): string {
  if (value == null) return "";
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
    return String(value);
  }
  if (Array.isArray(value)) return value.map(cell).filter(Boolean).join(", ");
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

export function asList(value: unknown): string[] {
  if (Array.isArray(value)) return value.map(cell).filter(Boolean);
  const text = cell(value);
  return text ? [text] : [];
}

export function asProps(value: unknown): Record<string, string> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  const props: Record<string, string> = {};
  for (const [key, raw] of Object.entries(value)) {
    const text = cell(raw);
    if (text) props[key] = text;
  }
  return props;
}

export function hash(value: string): number {
  let h = 2166136261;
  for (let i = 0; i < value.length; i += 1) {
    h ^= value.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}

export function clip(value: string, max: number): string {
  const clean = value.replace(/\s+/g, " ").trim();
  if (clean.length <= max) return clean;
  return `${clean.slice(0, max - 1)}…`;
}

export function localTail(value: string): string {
  const mapped = value.includes("://") ? value : value.replace(/__/g, "/");
  const cut = Math.max(mapped.lastIndexOf("#"), mapped.lastIndexOf("/"));
  const tail = (cut >= 0 ? mapped.slice(cut + 1) : mapped).trim();
  try {
    return decodeURIComponent(tail);
  } catch {
    return tail;
  }
}

export function friendly(value: string): string {
  const tail = localTail(value);
  if (!tail) return clip(value, 48);
  const spaced = tail.replace(/[_-]+/g, " ").replace(/([a-z\d])([A-Z])/g, "$1 $2").trim();
  const compact = spaced.replace(/\s/g, "");
  if (/^[0-9a-f-]{16,}$/i.test(compact)) {
    return `${compact.slice(0, 8)}…${compact.slice(-4)}`;
  }
  return clip(spaced || value, 48);
}

export function prettyPredicate(predicate: string): string {
  const words = localTail(predicate)
    .replace(/[_-]+/g, " ")
    .replace(/([a-z\d])([A-Z])/g, "$1 $2")
    .split(/\s+/)
    .filter(Boolean)
    .map((word, index) =>
      index === 0 ? word.charAt(0).toUpperCase() + word.slice(1).toLowerCase() : word.toLowerCase()
    );
  return words.join(" ") || predicate;
}

export function isNamePredicate(predicate: string): boolean {
  if (
    predicate === RDF.name ||
    predicate === RDF.label ||
    predicate === RDF.title ||
    predicate === RDF.schemaName
  ) {
    return true;
  }
  return NAME_TAILS.has(localTail(predicate).toLowerCase());
}

export function isTypePredicate(predicate: string): boolean {
  return predicate === RDF.type || localTail(predicate).toLowerCase() === "type";
}

export function isHiddenProp(key: string): boolean {
  return key === "uri" || key.toLowerCase().includes("claimsgraphuri");
}

export function isIri(value: string): boolean {
  if (!value || value.length > 2000 || /\s/.test(value)) return false;
  return /^[a-z][a-z0-9+.-]*:\/\//i.test(value) || /^(urn|did):/i.test(value);
}

export function usableId(value: string): boolean {
  return value.length > 0 && !value.startsWith("<<") && !value.startsWith("_:");
}

const SAFE_IRI = /^[^\s<>"{}|\\^`]+$/;

export function sparqlIri(iri: string): string {
  if (iri.length > 2000 || !SAFE_IRI.test(iri)) {
    throw new Error("This identifier cannot be opened.");
  }
  return `<${iri}>`;
}

export function quoteLiteral(value: string): string {
  if (value.length > 2000 || value.includes("\0")) {
    throw new Error("This identifier cannot be opened.");
  }
  const clean = value.replace(/[\u0000-\u001f]/g, " ");
  return `"${clean.replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"`;
}

export function displayTypes(types: string[]): string[] {
  return types.filter((type) => !GENERIC_TYPES.has(localTail(type).toLowerCase()));
}

export function colorFor(types: string[]): string {
  const fallback = PALETTE[0] ?? "#1565C0";
  const key = displayTypes(types)[0];
  if (!key) return fallback;
  return PALETTE[hash(key) % PALETTE.length] ?? fallback;
}

/** Rejects a query that would ask the catalogue to write, after strings and IRIs are ignored. */
export function assertReadQuery(query: string): string {
  const stripped = query
    .replace(/<[^>]*>/g, " ")
    .replace(/"(?:\\.|[^"])*"/g, " ")
    .replace(/'(?:\\.|[^'])*'/g, " ");
  if (WRITE_QUERY.test(stripped)) {
    throw new Error("That lookup was refused.");
  }
  return query;
}
