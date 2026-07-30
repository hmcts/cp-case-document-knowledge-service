package uk.gov.hmcts.cp.cdk.scheduler;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import uk.gov.hmcts.cp.cdk.services.DiscoveryService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IntradayDiscoverySchedulerTest {

    @Mock
    private DiscoveryService discoveryService;

    private IntradayDiscoveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new IntradayDiscoveryScheduler(discoveryService);
    }

    @Test
    void run_shouldTriggerIntradayDiscovery() {
        // when
        scheduler.run();

        // then
        verify(discoveryService, times(1)).runIntradayDiscovery();
        verifyNoMoreInteractions(discoveryService);
    }

    @Test
    void run_shouldBeCallableMultipleTimes() {
        // when
        scheduler.run();
        scheduler.run();

        // then
        verify(discoveryService, times(2)).runIntradayDiscovery();
    }
}