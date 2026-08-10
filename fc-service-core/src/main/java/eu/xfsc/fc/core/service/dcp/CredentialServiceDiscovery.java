package eu.xfsc.fc.core.service.dcp;

import de.eecc.dcp.Constants;
import eu.xfsc.fc.core.exception.ClientException;
import eu.xfsc.fc.core.service.resolve.DidDocumentResolver;
import foundation.identity.did.DIDDocument;
import foundation.identity.did.Service;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resolves the holder's Credential Service base URL from their DID document
 * ({@code service} entries with type {@link Constants#CREDENTIAL_SERVICE_TYPE}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CredentialServiceDiscovery {

  private final DidDocumentResolver didResolver;

  public String resolveCredentialServiceUrl(String holderDid) {
    DIDDocument document = didResolver.resolveDidDocument(holderDid);
    List<Service> services = document.getServices();
    if (services == null || services.isEmpty()) {
      throw new ClientException(
          "DID document for '" + holderDid + "' has no service endpoints");
    }

    for (Service service : services) {
      if (!isCredentialService(service)) {
        continue;
      }
      String endpoint = extractEndpoint(service.getServiceEndpoint());
      if (endpoint != null && !endpoint.isBlank()) {
        String base = stripTrailingSlash(endpoint.strip());
        log.debug("resolveCredentialServiceUrl; holderDid={}, csUrl={}", holderDid, base);
        return base;
      }
    }
    throw new ClientException(
        "DID document for '" + holderDid + "' has no CredentialService endpoint");
  }

  private static boolean isCredentialService(Service service) {
    if (service == null) {
      return false;
    }
    if (service.isType(Constants.CREDENTIAL_SERVICE_TYPE)) {
      return true;
    }
    List<String> types = service.getTypes();
    if (types == null) {
      return false;
    }
    return types.stream().anyMatch(Constants.CREDENTIAL_SERVICE_TYPE::equals);
  }

  private static String extractEndpoint(Object endpoint) {
    if (endpoint == null) {
      return null;
    }
    if (endpoint instanceof String s) {
      return s;
    }
    if (endpoint instanceof Map<?, ?> map) {
      Object id = map.get("id");
      if (id != null) {
        return String.valueOf(id);
      }
      Object uri = map.get("uri");
      if (uri != null) {
        return String.valueOf(uri);
      }
      Object url = map.get("url");
      if (url != null) {
        return String.valueOf(url);
      }
      return null;
    }
    if (endpoint instanceof Collection<?> collection) {
      for (Object entry : collection) {
        String resolved = extractEndpoint(entry);
        if (resolved != null && !resolved.isBlank()) {
          return resolved;
        }
      }
    }
    return String.valueOf(endpoint);
  }

  private static String stripTrailingSlash(String url) {
    if (url.endsWith("/") && url.length() > 1) {
      return url.substring(0, url.length() - 1);
    }
    return url;
  }
}
