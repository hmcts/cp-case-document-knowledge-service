package uk.gov.hmcts.cp.cdk.scheduler;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import uk.gov.hmcts.cp.cdk.services.DiscoveryService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Nightly Discovery Scheduler tests")
class NightlyDiscoverySchedulerTest {

    @Mock
    private DiscoveryService discoveryService;

    private NightlyDiscoveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new NightlyDiscoveryScheduler(discoveryService);
    }

    @Test
    @DisplayName("run should trigger nightly discovery")
    void run_shouldTriggerNightlyDiscovery() {
        // when
        scheduler.run();

        // then
        verify(discoveryService, times(1)).runNightlyDiscovery();
        verifyNoMoreInteractions(discoveryService);
    }

    @Test
    @DisplayName("run should be callable multiple times")
    void run_shouldBeCallableMultipleTimes() {
        // when
        scheduler.run();
        scheduler.run();

        // then
        verify(discoveryService, times(2)).runNightlyDiscovery();
    }
}
