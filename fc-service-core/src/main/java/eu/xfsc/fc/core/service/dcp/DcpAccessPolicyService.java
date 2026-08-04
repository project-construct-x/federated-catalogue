package eu.xfsc.fc.core.service.dcp;

import de.eecc.dcp.api.access.PresentationAccessPolicy;
import de.eecc.dcp.api.access.PresentationAccessRule;
import eu.xfsc.fc.core.dao.dcp.DcpAccessEffect;
import eu.xfsc.fc.core.dao.dcp.DcpAccessRule;
import eu.xfsc.fc.core.dao.dcp.DcpAccessRuleRepository;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads DCP presentation access whitelist rules from Postgres and builds a
 * {@link PresentationAccessPolicy} for the EECC DCP library.
 */
@Service
@RequiredArgsConstructor
public class DcpAccessPolicyService {

  private final DcpAccessRuleRepository accessRuleRepository;

  @Transactional(readOnly = true)
  public PresentationAccessPolicy loadPolicy() {
    List<DcpAccessRule> rows = accessRuleRepository.findByEnabledTrueOrderBySortOrderAscIdAsc();
    if (rows.isEmpty()) {
      return PresentationAccessPolicy.denyAll();
    }
    List<PresentationAccessRule> rules = rows.stream().map(this::toLibraryRule).toList();
    return PresentationAccessPolicy.of(rules);
  }

  private PresentationAccessRule toLibraryRule(DcpAccessRule row) {
    PresentationAccessRule.Builder builder =
        row.getEffect() == DcpAccessEffect.DENY
            ? PresentationAccessRule.deny()
            : PresentationAccessRule.allow();

    String[] verifiers = row.getVerifiers();
    if (verifiers != null && verifiers.length > 0) {
      builder.verifiers(Arrays.asList(verifiers));
    }

    String[] types = row.getCredentialTypes();
    if (types != null && types.length > 0) {
      builder.credentialTypes(Arrays.asList(types));
    }

    return builder.build();
  }
}
