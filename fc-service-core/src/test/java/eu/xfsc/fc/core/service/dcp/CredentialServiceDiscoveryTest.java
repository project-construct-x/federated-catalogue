package eu.xfsc.fc.core.service.dcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import eu.xfsc.fc.core.exception.ClientException;
import eu.xfsc.fc.core.service.resolve.DidDocumentResolver;
import foundation.identity.did.DIDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CredentialServiceDiscoveryTest {

  private static final String HOLDER = "did:web:holder.example";

  @Mock
  private DidDocumentResolver didResolver;

  @InjectMocks
  private CredentialServiceDiscovery discovery;

  @Test
  void resolve_findsCredentialServiceEndpoint() {
    DIDDocument doc = DIDDocument.fromJson("""
        {
          "id": "did:web:holder.example",
          "service": [{
            "id": "did:web:holder.example#cs",
            "type": "CredentialService",
            "serviceEndpoint": "https://cs.holder.example/api/"
          }]
        }
        """);
    when(didResolver.resolveDidDocument(HOLDER)).thenReturn(doc);

    String url = discovery.resolveCredentialServiceUrl(HOLDER);

    assertEquals("https://cs.holder.example/api", url);
  }

  @Test
  void resolve_supportsServiceEndpointObject() {
    DIDDocument doc = DIDDocument.fromJson("""
        {
          "id": "did:web:holder.example",
          "service": [{
            "id": "did:web:holder.example#cs",
            "type": ["CredentialService"],
            "serviceEndpoint": { "id": "https://cs.holder.example" }
          }]
        }
        """);
    when(didResolver.resolveDidDocument(HOLDER)).thenReturn(doc);

    assertEquals("https://cs.holder.example", discovery.resolveCredentialServiceUrl(HOLDER));
  }

  @Test
  void resolve_rejectsMissingCredentialService() {
    DIDDocument doc = DIDDocument.fromJson("""
        {
          "id": "did:web:holder.example",
          "service": [{
            "id": "did:web:holder.example#other",
            "type": "LinkedDomains",
            "serviceEndpoint": "https://holder.example"
          }]
        }
        """);
    when(didResolver.resolveDidDocument(HOLDER)).thenReturn(doc);

    ClientException ex = assertThrows(ClientException.class,
        () -> discovery.resolveCredentialServiceUrl(HOLDER));
    assertTrue(ex.getMessage().contains("CredentialService"));
  }
}
