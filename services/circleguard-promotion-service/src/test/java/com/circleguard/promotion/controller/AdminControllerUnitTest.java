package com.circleguard.promotion.controller;

import com.circleguard.promotion.model.jpa.SystemSettings;
import com.circleguard.promotion.repository.jpa.SystemSettingsRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminControllerUnitTest {

    @Test
    void shouldInitializeDefaultsWhenSettingsAreMissing() {
        SystemSettingsRepository repository = mock(SystemSettingsRepository.class);
        when(repository.getSettings()).thenReturn(Optional.empty());
        when(repository.save(any(SystemSettings.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SystemSettings settings = new AdminController(repository).getSettings().getBody();

        assertEquals(true, settings.getUnconfirmedFencingEnabled());
        assertEquals(3600L, settings.getAutoThresholdSeconds());
    }

    @Test
    void shouldUpdateOnlyProvidedSettingsAndToggleFencing() {
        SystemSettingsRepository repository = mock(SystemSettingsRepository.class);
        SystemSettings existing = SystemSettings.builder()
                .unconfirmedFencingEnabled(true)
                .autoThresholdSeconds(3600L)
                .mandatoryFenceDays(14)
                .encounterWindowDays(14)
                .build();
        when(repository.getSettings()).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        AdminController controller = new AdminController(repository);
        SystemSettings update = SystemSettings.builder().autoThresholdSeconds(120L).mandatoryFenceDays(7).build();

        assertEquals(120L, controller.updateSettings(update).getBody().getAutoThresholdSeconds());
        assertEquals(7, existing.getMandatoryFenceDays());
        assertEquals(false, controller.toggleUnconfirmedFencing(false).getBody().getUnconfirmedFencingEnabled());
        verify(repository, atLeastOnce()).save(existing);
    }
}
