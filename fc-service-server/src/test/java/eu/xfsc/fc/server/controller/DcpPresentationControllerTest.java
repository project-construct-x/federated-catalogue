package eu.xfsc.fc.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.eecc.dcp.exception.DcpException;
import de.eecc.dcp.exception.InvalidSelfIssuedIdToken;
import de.eecc.dcp.message.PresentationResponseMessage;
import eu.xfsc.fc.core.service.dcp.DcpVerifierService;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@AutoConfigureEmbeddedDatabase(provider = DatabaseProvider.ZONKY)
class DcpPresentationControllerTest {

  @Autowired
  private WebApplicationContext context;

  @Autowired
  private ObjectMapper objectMapper;

  private MockMvc mockMvc;

  @MockBean
  private DcpVerifierService dcpVerifierService;

  @BeforeAll
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void pullPresentations_withoutAuthorization_returns401() throws Exception {
    when(dcpVerifierService.pullPresentations(eq("Bearer"), any()))
        .thenThrow(new DcpException(new InvalidSelfIssuedIdToken(
            "Authorization Bearer Self-Issued ID Token is required")));

    mockMvc.perform(MockMvcRequestBuilders.post("/dcp/presentations")
            .header("Authorization", "Bearer")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .with(csrf()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void pullPresentations_happyPath_returnsPresentationResponse() throws Exception {
    JsonNode presentation = objectMapper.readTree("""
        {
          "type": ["VerifiablePresentation"],
          "verifiableCredential": [{
            "type": ["MembershipCredential"],
            "credentialSubject": { "id": "did:web:holder.example" }
          }]
        }
        """);
    PresentationResponseMessage response = new PresentationResponseMessage(
        List.of("https://w3id.org/dspace-dcp/v1.0/dcp.jsonld"),
        "PresentationResponseMessage",
        List.of(presentation),
        null);
    when(dcpVerifierService.pullPresentations(eq("Bearer client-si"), eq("POST_ASSETS")))
        .thenReturn(response);

    mockMvc.perform(MockMvcRequestBuilders.post("/dcp/presentations")
            .header("Authorization", "Bearer client-si")
            .queryParam("purpose", "POST_ASSETS")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("PresentationResponseMessage"))
        .andExpect(jsonPath("$.presentation").isArray());
  }
}
