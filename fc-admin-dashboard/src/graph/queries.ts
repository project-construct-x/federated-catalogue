import {
  RDF,
  assertReadQuery,
  quoteLiteral,
  sparqlIri
} from "./terms";

const LOBBY_LIMIT = 28;
const LINK_LIMIT = 80;
const NEIGHBOR_LIMIT = 60;
const SEARCH_LIMIT = 24;

const NAME_IRIS = [RDF.name, RDF.schemaName, RDF.label, RDF.title];

function nameFilter(variable: string): string {
  return `FILTER(${variable} IN (${NAME_IRIS.map((iri) => `<${iri}>`).join(", ")}))`;
}

function starLobby(): string {
  return `PREFIX cred: <${RDF.credential}>
SELECT ?subject (SAMPLE(?name) AS ?label) (SAMPLE(?type) AS ?kind)
WHERE {
  <<(?subject ?p ?o)>> cred:credentialSubject ?cs .
  OPTIONAL {
    <<(?subject ?np ?name)>> cred:credentialSubject ?csName .
    ${nameFilter("?np")}
  }
  OPTIONAL {
    <<(?subject <${RDF.type}> ?type)>> cred:credentialSubject ?csType .
  }
}
GROUP BY ?subject
ORDER BY ?subject
LIMIT ${LOBBY_LIMIT}`;
}

function plainLobby(): string {
  return `SELECT ?subject (SAMPLE(?name) AS ?label) (SAMPLE(?type) AS ?kind)
WHERE {
  ?subject ?p ?o .
  FILTER(isIRI(?subject))
  OPTIONAL {
    ?subject ?np ?name .
    ${nameFilter("?np")}
  }
  OPTIONAL { ?subject <${RDF.type}> ?type . }
}
GROUP BY ?subject
ORDER BY ?subject
LIMIT ${LOBBY_LIMIT}`;
}

function starLinks(iris: string[]): string {
  const values = iris.map((iri) => sparqlIri(iri)).join(" ");
  return `PREFIX cred: <${RDF.credential}>
SELECT ?subject ?predicate ?object
WHERE {
  VALUES ?subject { ${values} }
  <<(?subject ?predicate ?object)>> cred:credentialSubject ?cs .
  FILTER(isIRI(?object))
  FILTER(?predicate != <${RDF.type}>)
}
LIMIT ${LINK_LIMIT}`;
}

function plainLinks(iris: string[]): string {
  const values = iris.map((iri) => sparqlIri(iri)).join(" ");
  return `SELECT ?subject ?predicate ?object
WHERE {
  VALUES ?subject { ${values} }
  ?subject ?predicate ?object .
  FILTER(isIRI(?object))
  FILTER(?predicate != <${RDF.type}>)
}
LIMIT ${LINK_LIMIT}`;
}

function starNeighborhood(iri: string): string {
  const term = sparqlIri(iri);
  return `PREFIX cred: <${RDF.credential}>
SELECT ?subject ?predicate ?object
WHERE {
  {
    <<(${term} ?predicate ?object)>> cred:credentialSubject ?cs .
    BIND(${term} AS ?subject)
  }
  UNION
  {
    <<(?subject ?predicate ${term})>> cred:credentialSubject ?cs .
    BIND(${term} AS ?object)
  }
}
LIMIT ${NEIGHBOR_LIMIT}`;
}

function plainNeighborhood(iri: string): string {
  const term = sparqlIri(iri);
  return `SELECT ?subject ?predicate ?object
WHERE {
  { ${term} ?predicate ?object . BIND(${term} AS ?subject) }
  UNION
  { ?subject ?predicate ${term} . BIND(${term} AS ?object) }
}
LIMIT ${NEIGHBOR_LIMIT}`;
}

function starNames(iris: string[]): string {
  const values = iris.map((iri) => sparqlIri(iri)).join(" ");
  return `PREFIX cred: <${RDF.credential}>
SELECT ?subject (SAMPLE(?name) AS ?label) (SAMPLE(?type) AS ?kind)
WHERE {
  VALUES ?subject { ${values} }
  OPTIONAL {
    <<(?subject ?np ?name)>> cred:credentialSubject ?csName .
    ${nameFilter("?np")}
  }
  OPTIONAL {
    <<(?subject <${RDF.type}> ?type)>> cred:credentialSubject ?csType .
  }
}
GROUP BY ?subject
LIMIT ${iris.length}`;
}

function plainNames(iris: string[]): string {
  const values = iris.map((iri) => sparqlIri(iri)).join(" ");
  return `SELECT ?subject (SAMPLE(?name) AS ?label) (SAMPLE(?type) AS ?kind)
WHERE {
  VALUES ?subject { ${values} }
  OPTIONAL {
    ?subject ?np ?name .
    ${nameFilter("?np")}
  }
  OPTIONAL { ?subject <${RDF.type}> ?type . }
}
GROUP BY ?subject
LIMIT ${iris.length}`;
}

function searchFilter(term: string): string {
  const needle = quoteLiteral(term);
  return `FILTER(CONTAINS(LCASE(STR(?subject)), ${needle}) || CONTAINS(LCASE(STR(?o)), ${needle}) || CONTAINS(LCASE(STR(?p)), ${needle}))`;
}

function starSearch(term: string): string {
  return `PREFIX cred: <${RDF.credential}>
SELECT ?subject (SAMPLE(?name) AS ?label) (SAMPLE(?type) AS ?kind)
WHERE {
  <<(?subject ?p ?o)>> cred:credentialSubject ?cs .
  ${searchFilter(term)}
  OPTIONAL {
    <<(?subject ?np ?name)>> cred:credentialSubject ?csName .
    ${nameFilter("?np")}
  }
  OPTIONAL {
    <<(?subject <${RDF.type}> ?type)>> cred:credentialSubject ?csType .
  }
}
GROUP BY ?subject
ORDER BY ?subject
LIMIT ${SEARCH_LIMIT}`;
}

function plainSearch(term: string): string {
  return `SELECT ?subject (SAMPLE(?name) AS ?label) (SAMPLE(?type) AS ?kind)
WHERE {
  ?subject ?p ?o .
  FILTER(isIRI(?subject))
  ${searchFilter(term)}
  OPTIONAL {
    ?subject ?np ?name .
    ${nameFilter("?np")}
  }
  OPTIONAL { ?subject <${RDF.type}> ?type . }
}
GROUP BY ?subject
ORDER BY ?subject
LIMIT ${SEARCH_LIMIT}`;
}

function starType(type: string): string {
  const term = sparqlIri(type);
  return `PREFIX cred: <${RDF.credential}>
SELECT ?subject (SAMPLE(?name) AS ?label) (SAMPLE(?type) AS ?kind)
WHERE {
  <<(?subject <${RDF.type}> ${term})>> cred:credentialSubject ?cs .
  OPTIONAL {
    <<(?subject ?np ?name)>> cred:credentialSubject ?csName .
    ${nameFilter("?np")}
  }
  OPTIONAL {
    <<(?subject <${RDF.type}> ?type)>> cred:credentialSubject ?csType .
  }
}
GROUP BY ?subject
ORDER BY ?subject
LIMIT ${SEARCH_LIMIT}`;
}

function plainType(type: string): string {
  const term = sparqlIri(type);
  return `SELECT ?subject (SAMPLE(?name) AS ?label) (SAMPLE(?type) AS ?kind)
WHERE {
  ?subject <${RDF.type}> ${term} .
  OPTIONAL {
    ?subject ?np ?name .
    ${nameFilter("?np")}
  }
  OPTIONAL { ?subject <${RDF.type}> ?type . }
}
GROUP BY ?subject
ORDER BY ?subject
LIMIT ${SEARCH_LIMIT}`;
}

function cypherLobby(): string {
  return `MATCH (n:Resource)
RETURN n.uri AS subject, coalesce(n.name, n.label) AS name, labels(n) AS types
ORDER BY subject
LIMIT ${LOBBY_LIMIT}`;
}

function cypherLinks(iris: string[]): string {
  const list = iris.map((iri) => quoteLiteral(iri)).join(", ");
  return `MATCH (a:Resource)-[r]->(b:Resource)
WHERE a.uri IN [${list}] AND b.uri IS NOT NULL
RETURN a.uri AS subject, type(r) AS predicate, b.uri AS object, coalesce(b.name, b.label) AS objectName, labels(b) AS objectTypes
ORDER BY subject, predicate
LIMIT ${LINK_LIMIT}`;
}

function cypherNode(iri: string): string {
  const quoted = quoteLiteral(iri);
  return `MATCH (n:Resource {uri: ${quoted}})
RETURN n.uri AS uri, coalesce(n.name, n.label) AS name, labels(n) AS types, properties(n) AS props
LIMIT 1`;
}

function cypherNodePlain(iri: string): string {
  const quoted = quoteLiteral(iri);
  return `MATCH (n:Resource {uri: ${quoted}})
RETURN n.uri AS uri, coalesce(n.name, n.label) AS name, labels(n) AS types
LIMIT 1`;
}

function cypherHops(iri: string): string {
  const quoted = quoteLiteral(iri);
  return `MATCH (n:Resource {uri: ${quoted}})-[r]-(m:Resource)
WHERE m.uri IS NOT NULL AND m.uri <> n.uri
RETURN CASE WHEN startNode(r) = n THEN "out" ELSE "in" END AS direction, type(r) AS predicate, m.uri AS uri, coalesce(m.name, m.label) AS name, labels(m) AS types
LIMIT ${NEIGHBOR_LIMIT}`;
}

function cypherSearch(term: string): string {
  const needle = quoteLiteral(term);
  return `MATCH (n:Resource)
WHERE toLower(coalesce(toString(n.uri), "")) CONTAINS ${needle}
   OR toLower(coalesce(toString(n.name), "")) CONTAINS ${needle}
   OR toLower(coalesce(toString(n.label), "")) CONTAINS ${needle}
   OR any(key IN keys(n) WHERE toLower(coalesce(toString(n[key]), "")) CONTAINS ${needle})
RETURN n.uri AS subject, coalesce(n.name, n.label) AS name, labels(n) AS types
ORDER BY subject
LIMIT ${SEARCH_LIMIT}`;
}

function cypherType(type: string): string {
  const label = quoteLiteral(type);
  return `MATCH (n:Resource)
WHERE ${label} IN labels(n)
RETURN n.uri AS subject, coalesce(n.name, n.label) AS name, labels(n) AS types
ORDER BY subject
LIMIT ${SEARCH_LIMIT}`;
}

export const LIMITS = {
  lobby: LOBBY_LIMIT,
  search: SEARCH_LIMIT,
  links: LINK_LIMIT,
  neighbor: NEIGHBOR_LIMIT
};

export function lobbyQueries(cypher: boolean): string[] {
  return (cypher ? [cypherLobby()] : [starLobby(), plainLobby()]).map(assertReadQuery);
}

export function linkQuery(cypher: boolean, iris: string[]): string | null {
  const safe = iris.filter(canEmbed);
  if (safe.length === 0) return null;
  return assertReadQuery(cypher ? cypherLinks(safe) : starLinks(safe));
}

export function plainLinkQuery(iris: string[]): string | null {
  const safe = iris.filter(canEmbed);
  if (safe.length === 0) return null;
  return assertReadQuery(plainLinks(safe));
}

function canEmbed(iri: string): boolean {
  try {
    sparqlIri(iri);
    quoteLiteral(iri);
    return true;
  } catch {
    return false;
  }
}

export function neighborhoodQueries(iri: string): string[] {
  return [starNeighborhood(iri), plainNeighborhood(iri)].map(assertReadQuery);
}

export function nameQueries(iris: string[]): string[] {
  const safe = iris.filter(canEmbed);
  if (safe.length === 0) return [];
  return [starNames(safe), plainNames(safe)].map(assertReadQuery);
}

export function searchQueries(cypher: boolean, term: string): string[] {
  return (cypher ? [cypherSearch(term)] : [starSearch(term), plainSearch(term)]).map(assertReadQuery);
}

export function typeQueries(cypher: boolean, type: string): string[] {
  if (cypher) return [assertReadQuery(cypherType(type))];
  return [starType(type), plainType(type)].map(assertReadQuery);
}

export function nodeQueries(iri: string): string[] {
  return [cypherNode(iri), cypherNodePlain(iri)].map(assertReadQuery);
}

export function hopQuery(iri: string): string {
  return assertReadQuery(cypherHops(iri));
}
