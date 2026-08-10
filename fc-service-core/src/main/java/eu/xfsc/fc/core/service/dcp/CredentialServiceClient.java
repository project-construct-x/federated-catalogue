package eu.xfsc.fc.core.service.dcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.eecc.dcp.api.DcpOptions;
import de.eecc.dcp.api.DcpPresentation;
import de.eecc.dcp.exception.CredentialServiceError;
import de.eecc.dcp.exception.DcpException;
import de.eecc.dcp.message.PresentationQueryMessage;
import de.eecc.dcp.message.PresentationResponseMessage;
import eu.xfsc.fc.core.config.DcpProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * HTTP client for verifier outbound {@code POST /presentations/query} calls.
 */
@Slf4j
@Component
public class CredentialServiceClient {

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final Duration timeout;
  private final DcpPresentation dcpPresentation;

  public CredentialServiceClient(ObjectMapper objectMapper, DcpProperties properties) {
    this.objectMapper = objectMapper;
    this.timeout = properties.getHttpTimeout();
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .build();
    this.dcpPresentation = DcpPresentation.create(DcpOptions.builder().build());
  }

  public PresentationResponseMessage queryPresentations(
      String credentialServiceBaseUrl,
      PresentationQueryMessage queryMessage,
      String verifierSiToken) {
    String url = dcpPresentation.presentationsQueryUrl(credentialServiceBaseUrl);
    try {
      byte[] body = objectMapper.writeValueAsBytes(queryMessage);
      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
          .timeout(timeout)
          .header("Content-Type", "application/json")
          .header("Accept", "application/json")
          .header("Authorization", "Bearer " + verifierSiToken)
          .POST(HttpRequest.BodyPublishers.ofByteArray(body))
          .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new DcpException(new CredentialServiceError(
            "Credential Service returned HTTP " + response.statusCode() + " for " + url
                + ": " + truncate(response.body())));
      }
      PresentationResponseMessage message =
          objectMapper.readValue(response.body(), PresentationResponseMessage.class);
      if (message == null || message.presentation() == null) {
        throw new DcpException(new CredentialServiceError(
            "Credential Service returned an empty PresentationResponseMessage from " + url));
      }
      log.debug("queryPresentations; url={}, presentationCount={}",
          url, message.presentation().size());
      return message;
    } catch (DcpException ex) {
      throw ex;
    } catch (IOException | InterruptedException | RuntimeException ex) {
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new DcpException(new CredentialServiceError(
          "Credential Service call failed for " + url + ": " + ex.getMessage()), ex);
    }
  }

  private static String truncate(String body) {
    if (body == null) {
      return "";
    }
    return body.length() <= 500 ? body : body.substring(0, 500) + "...";
  }
}
