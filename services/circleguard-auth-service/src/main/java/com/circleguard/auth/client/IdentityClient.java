package com.circleguard.auth.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

@Component
public class IdentityClient {
    // In a real microservice, this would use Feign or WebClient
    private final RestTemplate restTemplate = new RestTemplate();
    private final String identityMapUrl;

    public IdentityClient(@Value("${identity.service.url:http://localhost:8083}") String identityServiceBaseUrl) {
        String base = identityServiceBaseUrl.endsWith("/")
                ? identityServiceBaseUrl.substring(0, identityServiceBaseUrl.length() - 1)
                : identityServiceBaseUrl;
        this.identityMapUrl = base + "/api/v1/identities/map";
    }

    public UUID getAnonymousId(String realIdentity) {
        Map<String, String> request = Map.of("realIdentity", realIdentity);
        Map<?, ?> response = restTemplate.postForObject(identityMapUrl, request, Map.class);
        return UUID.fromString(response.get("anonymousId").toString());
    }
}
