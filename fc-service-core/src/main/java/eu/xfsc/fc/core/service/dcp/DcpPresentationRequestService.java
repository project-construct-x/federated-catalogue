package eu.xfsc.fc.core.service.dcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.eecc.dcp.exception.DcpException;
import de.eecc.dcp.exception.InvalidPresentationResponse;
import de.eecc.dcp.message.PresentationQueryMessage;
import de.eecc.dcp.message.PresentationResponseMessage;
import de.eecc.dcp.query.DcpScope;
import de.eecc.dcp.query.PresentationExchangeQueryDefinition;
import de.eecc.dcp.query.PresentationQueryDefinition;
import de.eecc.dcp.query.ScopeQueryDefinition;
import de.eecc.dcp.query.template.constructx.MembershipQueryDefinition;
import de.eecc.dcp.vp.PresentationParser;
import eu.xfsc.fc.core.config.DcpProperties;
import eu.xfsc.fc.core.dao.dcp.DcpPresentationRequestDefinition;
import eu.xfsc.fc.core.dao.dcp.DcpPresentationRequestDefinitionRepository;
import eu.xfsc.fc.core.dao.dcp.DcpQueryKind;
import eu.xfsc.fc.core.exception.ClientException;
import eu.xfsc.fc.core.exception.ServerException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads stored presentation request definitions and maps them to EECC
 * {@link PresentationQueryDefinition} instances. For {@link DcpPurposes#POST_ASSETS}, overlays
 * optional issuer / credential-type constraints from {@link DcpProperties#getPostAssets()}.
 */
@Service
@RequiredArgsConstructor
public class DcpPresentationRequestService {

  private final DcpPresentationRequestDefinitionRepository definitionRepository;
  private final ObjectMapper objectMapper;
  private final DcpProperties dcpProperties;

  @Transactional(readOnly = true)
  public Optional<DcpPresentationRequestDefinition> findEnabledByPurpose(String purpose) {
    return definitionRepository.findFirstByPurposeAndEnabledTrueOrderByIdAsc(purpose);
  }

  @Transactional(readOnly = true)
  public PresentationQueryDefinition requireQueryDefinition(String purpose) {
    DcpPresentationRequestDefinition entity = findEnabledByPurpose(purpose)
        .orElseThrow(() -> new ClientException(
            "No enabled DCP presentation request definition for purpose: " + purpose));
    return applyPostAssetsPropertyConstraints(purpose, toQueryDefinition(entity));
  }

  public PresentationQueryDefinition toQueryDefinition(DcpPresentationRequestDefinition entity) {
    PresentationQueryDefinition root = switch (entity.getQueryKind()) {
      case MEMBERSHIP -> MembershipQueryDefinition.INSTANCE;
      case SCOPE -> buildScopeDefinition(entity);
      case PRESENTATION_EXCHANGE -> buildPresentationExchangeDefinition(entity);
    };

    List<String> issuers = nonEmpty(entity.getRequiredIssuers());
    List<String> subjects = nonEmpty(entity.getRequiredSubjectIds());
    if (!issuers.isEmpty()) {
      root = root.requiresIssuers(issuers);
    }
    if (!subjects.isEmpty()) {
      root = root.requiresSubjectIds(subjects);
    }
    return root;
  }

  /**
   * Applies {@code federated-catalogue.dcp.post-assets.*} when purpose is {@link DcpPurposes#POST_ASSETS}.
   * Blank issuer / credential-type properties skip the corresponding constraint.
   */
  PresentationQueryDefinition applyPostAssetsPropertyConstraints(
      String purpose, PresentationQueryDefinition definition) {
    if (!DcpPurposes.POST_ASSETS.equals(purpose) || definition == null) {
      return definition;
    }
    DcpProperties.PostAssets cfg = dcpProperties.getPostAssets();
    String requiredType = blankToNull(cfg.getRequiredCredentialType());
    String requiredIssuer = blankToNull(cfg.getRequiredIssuer());
    if (requiredType == null && requiredIssuer == null) {
      return definition;
    }

    PresentationQueryDefinition result = definition;
    if (requiredType != null) {
      List<String> issuers = result.requiredIssuers();
      List<String> subjects = result.requiredSubjectIds();
      result = new RequiredCredentialTypeQueryDefinition(
          ScopeQueryDefinition.of(DcpScope.vcType(requiredType)), requiredType);
      if (!issuers.isEmpty()) {
        result = result.requiresIssuers(issuers);
      }
      if (!subjects.isEmpty()) {
        result = result.requiresSubjectIds(subjects);
      }
    }
    if (requiredIssuer != null) {
      result = result.requiresIssuer(requiredIssuer);
    }
    return result;
  }

  private PresentationQueryDefinition buildScopeDefinition(DcpPresentationRequestDefinition entity) {
    List<DcpScope> scopes = parseScopes(entity.getScopes());
    if (scopes.isEmpty()) {
      throw new ServerException(
          "DCP presentation request definition '" + entity.getId() + "' has query_kind SCOPE but no scopes");
    }
    return new ScopeQueryDefinition(scopes);
  }

  private PresentationQueryDefinition buildPresentationExchangeDefinition(
      DcpPresentationRequestDefinition entity) {
    if (entity.getPresentationDefinition() == null || entity.getPresentationDefinition().isBlank()) {
      throw new ServerException(
          "DCP presentation request definition '" + entity.getId()
              + "' has query_kind PRESENTATION_EXCHANGE but no presentation_definition");
    }
    try {
      JsonNode node = objectMapper.readTree(entity.getPresentationDefinition());
      return new PresentationExchangeQueryDefinition(node);
    } catch (JsonProcessingException ex) {
      throw new ServerException(
          "Invalid presentation_definition JSON for DCP definition '" + entity.getId() + "'", ex);
    }
  }

  private List<DcpScope> parseScopes(String scopesJson) {
    if (scopesJson == null || scopesJson.isBlank()) {
      return List.of();
    }
    try {
      JsonNode node = objectMapper.readTree(scopesJson);
      List<DcpScope> scopes = new ArrayList<>();
      if (node.isArray()) {
        for (JsonNode entry : node) {
          if (entry != null && entry.isTextual() && !entry.asText().isBlank()) {
            scopes.add(DcpScope.parse(entry.asText()));
          }
        }
      }
      return List.copyOf(scopes);
    } catch (JsonProcessingException | RuntimeException ex) {
      throw new ServerException("Invalid DCP scopes JSON: " + scopesJson, ex);
    }
  }

  private static List<String> nonEmpty(String[] values) {
    if (values == null || values.length == 0) {
      return List.of();
    }
    return Arrays.stream(values)
        .filter(v -> v != null && !v.isBlank())
        .map(String::trim)
        .distinct()
        .toList();
  }

  private static String blankToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.strip();
  }

  /**
   * Scope query for a configured VC type that also asserts the type is present in the response.
   */
  private static final class RequiredCredentialTypeQueryDefinition implements PresentationQueryDefinition {

    private final ScopeQueryDefinition scopeQuery;
    private final String requiredType;

    private RequiredCredentialTypeQueryDefinition(ScopeQueryDefinition scopeQuery, String requiredType) {
      this.scopeQuery = scopeQuery;
      this.requiredType = requiredType;
    }

    @Override
    public PresentationQueryMessage toQueryMessage() {
      return scopeQuery.toQueryMessage();
    }

    @Override
    public void assertResponseStructure(PresentationResponseMessage response) {
      scopeQuery.assertResponseStructure(response);
    }

    @Override
    public void assertQueryConstraints(PresentationResponseMessage response) {
      PresentationQueryDefinition.super.assertQueryConstraints(response);
      List<JsonNode> presentations = response.presentation();
      if (presentations == null) {
        return;
      }
      List<String> accepted = List.of(requiredType);
      for (JsonNode presentation : presentations) {
        String found = PresentationParser.extractCredentialType(presentation, accepted);
        if (found == null || !requiredType.equals(found)) {
          throw new DcpException(new InvalidPresentationResponse(
              "required credential type '" + requiredType + "' not present in presentation"));
        }
      }
    }
  }
}
