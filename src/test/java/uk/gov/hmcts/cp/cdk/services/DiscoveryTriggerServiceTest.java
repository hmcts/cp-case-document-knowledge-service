package uk.gov.hmcts.cp.cdk.services;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import uk.gov.hmcts.cp.openapi.model.cdk.DiscoveryOperation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;

@DisplayName("DiscoveryTriggerService tests (Story 3, DD-43062)")
class DiscoveryTriggerServiceTest {

    private final DiscoveryService discoveryService = mock(DiscoveryService.class);
    private final TaskExecutor discoveryTriggerExecutor = mock(TaskExecutor.class);
    private final DiscoveryTriggerService service =
            new DiscoveryTriggerService(discoveryService, discoveryTriggerExecutor);

    @Test
    @DisplayName("INTRADAY routes to runIntradayDiscovery via the executor, not the calling thread")
    void trigger_intraday_routesToRunIntradayDiscovery_offCallingThread() {
        service.trigger(DiscoveryOperation.INTRADAY);

        verify(discoveryService, never()).runIntradayDiscovery();
        final ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(discoveryTriggerExecutor).execute(captor.capture());

        captor.getValue().run();
        verify(discoveryService, times(1)).runIntradayDiscovery();
        verify(discoveryService, never()).runNightlyDiscovery();
    }

    @Test
    @DisplayName("NIGHTLY routes to runNightlyDiscovery via the executor, not the calling thread")
    void trigger_nightly_routesToRunNightlyDiscovery_offCallingThread() {
        service.trigger(DiscoveryOperation.NIGHTLY);

        final ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(discoveryTriggerExecutor).execute(captor.capture());

        captor.getValue().run();
        verify(discoveryService, times(1)).runNightlyDiscovery();
        verify(discoveryService, never()).runIntradayDiscovery();
    }

    @Test
    @DisplayName("an exception escaping the delegated run is caught and logged, not rethrown (AC-022)")
    void trigger_delegateThrows_isCaughtNotRethrown() {
        doThrow(new RuntimeException("boom")).when(discoveryService).runIntradayDiscovery();

        service.trigger(DiscoveryOperation.INTRADAY);

        final ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(discoveryTriggerExecutor).execute(captor.capture());

        assertThatCode(() -> captor.getValue().run()).doesNotThrowAnyException();
    }
}
