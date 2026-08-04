package eu.xfsc.fc.core.dao.dcp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Persistable DCP presentation request definition (mirror of
 * {@code de.eecc.dcp.query.PresentationQueryDefinition}).
 *
 * <p>{@code purpose} selects which definition applies to a catalogue endpoint
 * (e.g. {@code POST_ASSETS}).
 */
@Entity
@Table(name = "dcp_presentation_request_definitions")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DcpPresentationRequestDefinition {

  @Id
  @Column(name = "id", length = 128, nullable = false)
  private String id;

  @Column(name = "name", length = 255, nullable = false)
  private String name;

  /** Endpoint / use-case key, e.g. {@code POST_ASSETS}. */
  @Column(name = "purpose", length = 64, nullable = false)
  private String purpose;

  @Enumerated(EnumType.STRING)
  @Column(name = "query_kind", length = 32, nullable = false)
  private DcpQueryKind queryKind;

  /**
   * JSON array of DCP scope strings (e.g. {@code org.eclipse.dspace.dcp.vc.type:MembershipCredential}).
   * Used for {@link DcpQueryKind#SCOPE} and as documentation for {@link DcpQueryKind#MEMBERSHIP}.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "scopes", columnDefinition = "JSONB")
  private String scopes;

  /** Presentation Exchange {@code presentationDefinition} JSON object. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "presentation_definition", columnDefinition = "JSONB")
  private String presentationDefinition;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "required_issuers", columnDefinition = "text[]")
  private String[] requiredIssuers;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "required_subject_ids", columnDefinition = "text[]")
  private String[] requiredSubjectIds;

  @Column(name = "enabled", nullable = false)
  @Builder.Default
  private boolean enabled = true;

  @Column(name = "description", length = 1024)
  private String description;

  @CreatedDate
  @Column(name = "created_at", updatable = false)
  private Instant createdAt;

  @LastModifiedDate
  @Column(name = "updated_at")
  private Instant updatedAt;
}
