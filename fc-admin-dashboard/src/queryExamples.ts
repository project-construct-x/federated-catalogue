export interface QueryExample {
  id: string;
  title: string;
  hint: string;
  query: string;
}

const SPARQL: QueryExample[] = [
  {
    id: "sparql-any",
    title: "Anything stored",
    hint: "A sample of every triple in the graph. Zero rows means nothing has been indexed yet, which is normal before the first credential is posted.",
    query: `SELECT ?subject ?predicate ?object
WHERE { ?subject ?predicate ?object }
LIMIT 25`
  },
  {
    id: "sparql-claims",
    title: "Claims and credentials",
    hint: "Each row is one signed statement and the credential it was taken from. The catalogue stores claims as RDF-star triples annotated with credentialSubject.",
    query: `PREFIX cred: <https://www.w3.org/2018/credentials#>
SELECT ?subject ?predicate ?object ?credential
WHERE {
  <<(?subject ?predicate ?object)>> cred:credentialSubject ?credential
}
LIMIT 25`
  },
  {
    id: "sparql-predicates",
    title: "Predicates in use",
    hint: "Which predicates actually occur, most common first. Use this when you do not yet know the vocabulary in a thin graph.",
    query: `SELECT ?predicate (COUNT(*) AS ?uses)
WHERE { ?subject ?predicate ?object }
GROUP BY ?predicate
ORDER BY DESC(?uses)
LIMIT 40`
  },
  {
    id: "sparql-names",
    title: "Names",
    hint: "Resources that published a schema:name claim, such as a legal person or a service offering. Empty when no named credential has been posted.",
    query: `PREFIX cred: <https://www.w3.org/2018/credentials#>
PREFIX schema: <https://schema.org/>
SELECT ?entity ?name
WHERE {
  <<(?entity schema:name ?name)>> cred:credentialSubject ?entity
}
LIMIT 25`
  },
  {
    id: "sparql-services",
    title: "Service offerings",
    hint: "Gaia-X service offerings, with name and provider when those claims exist. Optional fields stay blank rather than dropping the row.",
    query: `PREFIX cred: <https://www.w3.org/2018/credentials#>
PREFIX gx: <https://w3id.org/gaia-x/2511#>
PREFIX schema: <https://schema.org/>
SELECT ?service ?name ?provider
WHERE {
  <<(?service a gx:ServiceOffering)>> cred:credentialSubject ?service .
  OPTIONAL { <<(?service schema:name ?name)>> cred:credentialSubject ?service }
  OPTIONAL { <<(?service gx:providedBy ?provider)>> cred:credentialSubject ?service }
}
LIMIT 25`
  },
  {
    id: "sparql-persons",
    title: "Legal persons",
    hint: "Participants typed as gx:LegalPerson. A fresh catalogue often has none until a participant credential is posted.",
    query: `PREFIX cred: <https://www.w3.org/2018/credentials#>
PREFIX gx: <https://w3id.org/gaia-x/2511#>
PREFIX schema: <https://schema.org/>
SELECT ?person ?name
WHERE {
  <<(?person a gx:LegalPerson)>> cred:credentialSubject ?person .
  OPTIONAL { <<(?person schema:name ?name)>> cred:credentialSubject ?person }
}
LIMIT 25`
  }
];

const CYPHER: QueryExample[] = [
  {
    id: "cypher-labels",
    title: "What is stored",
    hint: "How many nodes carry each label. A useful first check when the graph looks empty: zero rows means the store has no nodes.",
    query: `MATCH (n)
RETURN labels(n) AS labels, count(*) AS count
ORDER BY count DESC
LIMIT 25`
  },
  {
    id: "cypher-resources",
    title: "Resources",
    hint: "Nodes imported from credentials. uri is the subject identifier. name is present only when the credential carried one.",
    query: `MATCH (n:Resource)
RETURN n.uri AS id, n.name AS name
LIMIT 25`
  },
  {
    id: "cypher-keys",
    title: "Property names",
    hint: "Which properties each resource actually has. Use this when names or types are missing and you need the raw shape of a node.",
    query: `MATCH (n:Resource)
RETURN n.uri AS id, keys(n) AS properties
LIMIT 15`
  },
  {
    id: "cypher-links",
    title: "Links",
    hint: "Relationships between resources. An empty result means credentials were stored as isolated nodes, or nothing has been imported.",
    query: `MATCH (a:Resource)-[r]->(b)
RETURN a.uri AS from, type(r) AS relation, b.uri AS to
LIMIT 25`
  },
  {
    id: "cypher-lookup",
    title: "Find by identifier",
    hint: "Resources whose identifier contains a host or path you already know. Change example.org to a participant you posted.",
    query: `MATCH (n:Resource)
WHERE n.uri CONTAINS "example.org"
RETURN n.uri AS id
LIMIT 25`
  }
];

export function examplesFor(language?: string): QueryExample[] {
  return language?.toUpperCase() === "OPENCYPHER" ? CYPHER : SPARQL;
}
