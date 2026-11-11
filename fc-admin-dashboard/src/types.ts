export interface JwtProfile {
  sub?: string;
  name?: string;
  email?: string;
  preferred_username?: string;
  azp?: string;
  resource_access?: Record<string, { roles?: string[] }>;
}

export interface AdminStats {
  totalAssets?: number;
  activeAssets?: number;
  activeTrustFrameworks?: number;
  totalUsers?: number;
  totalSchemas?: number;
  totalParticipants?: number;
  graphClaimCount?: number;
  graphBackend?: string;
}

export interface AdminHealth {
  catalogueStatus?: string;
  graphDbStatus?: { connected?: boolean; backend?: string; claimCount?: number };
  keycloakStatus?: string;
  fileStoreStatus?: string;
  databaseStatus?: string;
  keycloakUrl?: string;
  fileStorePath?: string;
}

export interface GraphStatus {
  backend?: string;
  enabled?: boolean;
  healthy?: boolean;
  activeAssetCount?: number;
  claimCountInGraph?: number;
  assetCountInGraph?: number;
  syncAssessment?: string;
}

export interface PostedAsset {
  id?: string;
  assetHash?: string;
  status?: string;
  issuer?: string;
  contentType?: string;
  fileSize?: number;
  uploadDatetime?: string;
  statusDatetime?: string;
}

export interface PostedAssetPage {
  totalCount?: number;
  items?: PostedAsset[];
}

export interface PostedAssetContent {
  id?: string;
  contentType?: string;
  content?: string | null;
  binary?: boolean;
}

export interface QueryInfo {
  backend?: string;
  enabled?: boolean;
  queryLanguage?: string;
  contentType?: string;
  exampleQuery?: string;
  documentation?: string;
}

export interface QueryResults {
  totalCount?: number;
  items?: Record<string, unknown>[];
}

export interface SessionInfo {
  userId?: string;
  status?: string;
  roleIds?: string[];
  creationDatetime?: string;
}

export interface UserProfile {
  id?: string;
  username?: string;
  email?: string;
  firstName?: string;
  lastName?: string;
  roleIds?: string[];
}

export interface UserPage {
  totalCount?: number;
  items?: UserProfile[];
}

export interface OntologySchemas {
  ontologies?: string[];
  shapes?: string[];
  vocabularies?: string[];
  jsonSchemas?: string[];
  xmlSchemas?: string[];
}

export interface TrustFramework {
  id?: string;
  name?: string;
  enabled?: boolean;
  bundles?: { id?: string; baseClasses?: Record<string, boolean> }[];
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number
  ) {
    super(message);
  }
}
