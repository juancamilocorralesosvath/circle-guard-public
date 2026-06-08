package com.circleguard.dashboard.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PromotionClientTest {

    private PromotionClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        client = new PromotionClient();
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        server = MockRestServiceServer.bindTo(restTemplate).build();
        ReflectionTestUtils.setField(client, "promotionServiceUrl", "http://promotion-service");
    }

    @Test
    void shouldFetchCampusHealthStats() {
        server.expect(requestTo("http://promotion-service/api/v1/health-status/stats"))
                .andRespond(withSuccess("{\"activeCount\":10}", MediaType.APPLICATION_JSON));

        assertEquals(10, client.getHealthStats().get("activeCount"));
        server.verify();
    }

    @Test
    void shouldReturnFallbackCampusStatsWhenPromotionServiceFails() {
        server.expect(requestTo("http://promotion-service/api/v1/health-status/stats"))
                .andRespond(withServerError());

        assertEquals("Service unavailable", client.getHealthStats().get("error"));
    }

    @Test
    void shouldFetchDepartmentHealthStats() {
        server.expect(requestTo("http://promotion-service/api/v1/health-status/stats/department/engineering"))
                .andRespond(withSuccess("{\"department\":\"engineering\",\"totalUsers\":20}", MediaType.APPLICATION_JSON));

        assertEquals("engineering", client.getHealthStatsByDepartment("engineering").get("department"));
        server.verify();
    }

    @Test
    void shouldReturnFallbackDepartmentStatsWhenPromotionServiceFails() {
        server.expect(requestTo("http://promotion-service/api/v1/health-status/stats/department/engineering"))
                .andRespond(withServerError());

        var result = client.getHealthStatsByDepartment("engineering");

        assertEquals("Service unavailable", result.get("error"));
        assertEquals("engineering", result.get("department"));
        assertTrue(result.containsKey("timestamp"));
    }
}
