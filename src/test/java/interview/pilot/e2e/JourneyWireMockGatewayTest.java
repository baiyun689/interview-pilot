package interview.pilot.e2e;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;

import interview.pilot.ai.model.AiRequest;
import interview.pilot.resume.domain.ResumeProfile;
import tools.jackson.databind.ObjectMapper;

class JourneyWireMockGatewayTest {
  @Test
  void sendsRealPromptAndResponseContractToDeterministicFixture() {
    var server = new WireMockServer(options().dynamicPort());
    server.start();
    try {
      server.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
          .withRequestBody(matchingJsonPath("$.model", equalTo("snapshot-model")))
          .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
          .withRequestBody(matchingJsonPath("$.messages[0].content", equalTo("system contract")))
          .withRequestBody(matchingJsonPath("$.messages[1].role", equalTo("user")))
          .withRequestBody(matchingJsonPath("$.messages[1].content", equalTo("user evidence")))
          .withRequestBody(matchingJsonPath(
              "$.responseType", equalTo(ResumeProfile.class.getName())))
          .willReturn(aResponse().withHeader("Content-Type", "application/json")
              .withBody("{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}")));

      var gateway = new InterviewJourneyIT.WireMockGatewayConfiguration()
          .deterministicWireMockGateway(new ObjectMapper(), server.baseUrl());
      var response = gateway.generate(new AiRequest(
          "journey", "snapshot-model", "system contract", "user evidence",
          ResumeProfile.class));

      assertThat(response.content()).isEqualTo("{}");
      server.verify(1, postRequestedFor(urlPathEqualTo("/v1/chat/completions")));
    } finally {
      server.stop();
    }
  }
}
