package eu.xfsc.fc.core.dao.dcp;

/**
 * How a stored presentation request definition maps onto a
 * {@code de.eecc.dcp.query.PresentationQueryDefinition}.
 */
public enum DcpQueryKind {
  /** Scope-based query ({@code ScopeQueryDefinition}). */
  SCOPE,
  /** Presentation Exchange query ({@code PresentationExchangeQueryDefinition}). */
  PRESENTATION_EXCHANGE,
  /** Construct-X MembershipCredential template ({@code MembershipQueryDefinition}). */
  MEMBERSHIP
}
