package eu.xfsc.fc.core.service.dcp;

/**
 * Catalogue endpoint purposes used to select a stored DCP presentation request definition.
 */
public final class DcpPurposes {

  /** {@code POST /assets} — DCP {@code PresentationResponseMessage} ingest. */
  public static final String POST_ASSETS = "POST_ASSETS";

  private DcpPurposes() {}
}
