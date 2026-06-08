package com.circleguard.promotion.task;

import com.circleguard.promotion.repository.graph.UserNodeRepository;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GraphCleanupTaskUnitTest {

    @Test
    void shouldPurgeStaleEncountersAndSwallowFailures() {
        UserNodeRepository repository = mock(UserNodeRepository.class);
        new GraphCleanupTask(repository).purgeStaleEncounters();
        verify(repository).purgeStaleEncounters(anyLong());

        UserNodeRepository failingRepository = mock(UserNodeRepository.class);
        doThrow(new RuntimeException("neo4j down")).when(failingRepository).purgeStaleEncounters(anyLong());
        new GraphCleanupTask(failingRepository).purgeStaleEncounters();
        verify(failingRepository).purgeStaleEncounters(anyLong());
    }
}
