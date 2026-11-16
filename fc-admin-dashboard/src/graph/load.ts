import { apiPost } from "@/services/api";
import type { QueryInfo, QueryResults } from "@/types";
import {
  hopQuery,
  LIMITS,
  linkQuery,
  lobbyQueries,
  nameQueries,
  neighborhoodQueries,
  nodeQueries,
  plainLinkQuery,
  searchQueries,
  typeQueries
} from "./queries";
import type { Scene, Triple } from "./scene";
import { triplesToScene } from "./scene";
import { asList, asProps, cell, isHiddenProp, isIri, RDF, usableId } from "./terms";

export interface LoadedScene {
  scene: Scene;
  capped: boolean;
}

export function isCypher(info: QueryInfo): boolean {
  return info.queryLanguage?.toUpperCase() === "OPENCYPHER";
}

export async function loadLobby(info: QueryInfo): Promise<LoadedScene> {
  const rows = await firstRows(info, lobbyQueries(isCypher(info)));
  return gallery(info, rows, LIMITS.lobby);
}

export async function loadSearch(info: QueryInfo, term: string): Promise<LoadedScene> {
  const needle = term.trim().toLowerCase();
  if (needle.length < 2) throw new Error("Type at least 2 characters.");
  const rows = await firstRows(info, searchQueries(isCypher(info), needle));
  return gallery(info, rows, LIMITS.search);
}

export async function loadType(info: QueryInfo, type: string): Promise<LoadedScene> {
  const rows = await firstRows(info, typeQueries(isCypher(info), type));
  return gallery(info, rows, LIMITS.search);
}

export async function loadNeighborhood(info: QueryInfo, iri: string): Promise<LoadedScene> {
  if (isCypher(info)) return cypherNeighborhood(info, iri);
  return sparqlNeighborhood(info, iri);
}

async function gallery(info: QueryInfo, rows: Record<string, unknown>[], limit: number): Promise<LoadedScene> {
  const ids = rows.map((row) => cell(row.subject)).filter(usableId);
  const triples = seedTriples(rows);
  let capped = rows.length >= limit;
  if (ids.length > 0) {
    const linked = await linksOrEmpty(info, ids);
    triples.push(...linkTriples(linked));
    capped = capped || linked.length >= LIMITS.links;
  }
  return { scene: triplesToScene(triples, null, ids), capped };
}

async function sparqlNeighborhood(info: QueryInfo, iri: string): Promise<LoadedScene> {
  const rows = await firstRows(info, neighborhoodQueries(iri));
  const triples = rowTriples(rows);
  const neighbors = [...new Set(triples.flatMap((triple) => [triple.subject, triple.object]))]
    .filter((id) => id !== iri && isIri(id))
    .slice(0, 30);
  if (neighbors.length > 0) {
    try {
      triples.push(...seedTriples(await firstRows(info, nameQueries(neighbors))));
    } catch {
      // Names are a convenience. The links still open.
    }
  }
  return {
    scene: triplesToScene(triples, iri, [iri]),
    capped: rows.length >= LIMITS.neighbor
  };
}

async function linksOrEmpty(info: QueryInfo, ids: string[]): Promise<Record<string, unknown>[]> {
  const primary = linkQuery(isCypher(info), ids);
  if (!primary) return [];
  try {
    const linked = await run(info, primary);
    if (linked.length > 0 || isCypher(info)) return linked;
  } catch {
    if (isCypher(info)) return [];
  }
  const plain = plainLinkQuery(ids);
  if (!plain) return [];
  try {
    return await run(info, plain);
  } catch {
    return [];
  }
}

async function cypherNeighborhood(info: QueryInfo, iri: string): Promise<LoadedScene> {
  const [detailsResult, hopsResult] = await Promise.allSettled([
    firstRow(info, nodeQueries(iri)),
    run(info, hopQuery(iri))
  ]);
  if (detailsResult.status === "rejected" && hopsResult.status === "rejected") {
    throw detailsResult.reason;
  }
  const details = detailsResult.status === "fulfilled" ? detailsResult.value : undefined;
  const hops = hopsResult.status === "fulfilled" ? hopsResult.value : [];
  const triples: Triple[] = [];
  if (details) {
    const name = cell(details.name);
    if (name) triples.push({ subject: iri, predicate: RDF.name, object: name });
    for (const label of asList(details.types)) {
      if (label !== "Resource") triples.push({ subject: iri, predicate: RDF.type, object: label });
    }
    for (const [key, value] of Object.entries(asProps(details.props))) {
      if (!isHiddenProp(key)) triples.push({ subject: iri, predicate: key, object: value });
    }
  }
  for (const row of hops) {
    const other = cell(row.uri);
    const predicate = cell(row.predicate);
    if (!usableId(other) || !predicate) continue;
    if (cell(row.direction) === "in") triples.push({ subject: other, predicate, object: iri });
    else triples.push({ subject: iri, predicate, object: other });
    const name = cell(row.name);
    if (name) triples.push({ subject: other, predicate: RDF.name, object: name });
    for (const label of asList(row.types)) {
      if (label !== "Resource") triples.push({ subject: other, predicate: RDF.type, object: label });
    }
  }
  return { scene: triplesToScene(triples, iri, [iri]), capped: hops.length >= LIMITS.neighbor };
}

function seedTriples(rows: Record<string, unknown>[]): Triple[] {
  const triples: Triple[] = [];
  for (const row of rows) {
    const subject = cell(row.subject);
    if (!usableId(subject)) continue;
    const name = cell(row.label) || cell(row.name);
    if (name && name !== subject) triples.push({ subject, predicate: RDF.name, object: name });
    const type = cell(row.kind) || cell(row.type);
    if (type) triples.push({ subject, predicate: RDF.type, object: type });
    for (const label of asList(row.types)) {
      if (label && label !== "Resource") triples.push({ subject, predicate: RDF.type, object: label });
    }
  }
  return triples;
}

function linkTriples(rows: Record<string, unknown>[]): Triple[] {
  const triples: Triple[] = [];
  for (const row of rows) {
    const subject = cell(row.subject);
    const predicate = cell(row.predicate);
    const object = cell(row.object);
    if (!usableId(subject) || !predicate || !object) continue;
    triples.push({ subject, predicate, object });
    const objectName = cell(row.objectName);
    if (objectName) triples.push({ subject: object, predicate: RDF.name, object: objectName });
    for (const label of asList(row.objectTypes)) {
      if (label && label !== "Resource") triples.push({ subject: object, predicate: RDF.type, object: label });
    }
  }
  return triples;
}

function rowTriples(rows: Record<string, unknown>[]): Triple[] {
  return rows.flatMap((row) => {
    const subject = cell(row.subject);
    const predicate = cell(row.predicate);
    const object = cell(row.object);
    if (!usableId(subject) || !predicate || !object) return [];
    return [{ subject, predicate, object }];
  });
}

async function run(info: QueryInfo, query: string): Promise<Record<string, unknown>[]> {
  if (!info.enabled || !info.contentType) throw new Error("The graph store is not available.");
  const results = await apiPost<QueryResults>("/admin/query", query, info.contentType);
  return results.items ?? [];
}

async function firstRows(info: QueryInfo, queries: string[]): Promise<Record<string, unknown>[]> {
  let lastError: unknown;
  let succeeded = false;
  for (const query of queries) {
    try {
      const rows = await run(info, query);
      succeeded = true;
      if (rows.length > 0) return rows;
    } catch (cause) {
      lastError = cause;
    }
  }
  if (!succeeded) throw lastError ?? new Error("Could not read the graph.");
  return [];
}

async function firstRow(info: QueryInfo, queries: string[]): Promise<Record<string, unknown> | undefined> {
  const rows = await firstRows(info, queries);
  return rows[0];
}
