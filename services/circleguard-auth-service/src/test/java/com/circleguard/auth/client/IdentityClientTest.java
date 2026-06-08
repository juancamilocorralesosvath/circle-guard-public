package com.circleguard.auth.client;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IdentityClientTest {

    @Test
    void shouldMapRealIdentityToAnonymousId() {
        UUID anonymousId = UUID.randomUUID();
        IdentityClient client = new IdentityClient("http://identity-service/");
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://identity-service/api/v1/identities/map"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"anonymousId\":\"" + anonymousId + "\"}",
                        org.springframework.http.MediaType.APPLICATION_JSON));

        assertEquals(anonymousId, client.getAnonymousId("student@uni.edu"));
        server.verify();
    }
}
