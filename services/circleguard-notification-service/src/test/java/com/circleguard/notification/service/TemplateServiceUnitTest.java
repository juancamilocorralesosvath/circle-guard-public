package com.circleguard.notification.service;

import freemarker.template.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemplateServiceUnitTest {

    @Test
    void shouldReturnFallbackEmailWhenTemplateFails() throws Exception {
        Configuration configuration = mock(Configuration.class);
        when(configuration.getTemplate("health_alert.ftl")).thenThrow(new IOException("missing"));
        TemplateService service = new TemplateService(configuration);

        String content = service.generateEmailContent("CONFIRMED", null);

        assertTrue(content.contains("CONFIRMED"));
        assertTrue(content.contains("Please check the app"));
    }

    @Test
    void shouldGenerateSuspectAndDefaultPushContent() {
        TemplateService service = new TemplateService(mock(Configuration.class));
        ReflectionTestUtils.setField(service, "guidelinesDeepLink", "circleguard://guidelines");

        assertTrue(service.generatePushContent("SUSPECT").contains("isolation steps"));
        assertEquals("CircleGuard: Your health status has been updated to ACTIVE",
                service.generatePushContent("ACTIVE"));
        assertEquals("circleguard://guidelines", service.generatePushMetadata("PROBABLE").get("url"));
    }
}
