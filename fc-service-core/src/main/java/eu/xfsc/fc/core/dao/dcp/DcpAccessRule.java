package eu.xfsc.fc.core.dao.dcp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
 * One DCP {@link de.eecc.dcp.api.access.PresentationAccessRule} row.
 *
 * <p>Loaded into {@link de.eecc.dcp.api.access.PresentationAccessPolicy} for the EECC DCP library.
 * When the catalogue acts as a Credential Service, {@code verifiers} are verifier DIDs. Empty rule
 * list means deny-all (library default).
 */
@Entity
@Table(name = "dcp_access_rules")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DcpAccessRule {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(name = "effect", length = 16, nullable = false)
  private DcpAccessEffect effect;

  /** Verifier DIDs, or {@code *} for any. */
  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "verifiers", columnDefinition = "text[]", nullable = false)
  private String[] verifiers;

  /** Credential type names, or {@code *} for any. */
  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "credential_types", columnDefinition = "text[]", nullable = false)
  private String[] credentialTypes;

  @Column(name = "enabled", nullable = false)
  @Builder.Default
  private boolean enabled = true;

  @Column(name = "sort_order", nullable = false)
  @Builder.Default
  private int sortOrder = 0;

  @Column(name = "description", length = 512)
  private String description;

  @CreatedDate
  @Column(name = "created_at", updatable = false)
  private Instant createdAt;

  @LastModifiedDate
  @Column(name = "updated_at")
  private Instant updatedAt;
}
