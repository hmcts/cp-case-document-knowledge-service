package uk.gov.hmcts.cp.cdk.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("MdcCopyingTaskDecorator tests (Story 4, DD-43063)")
class MdcCopyingTaskDecoratorTest {

    private final MdcCopyingTaskDecorator decorator = new MdcCopyingTaskDecorator();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("MDC captured at decorate-time is visible inside the decorated runnable, on another thread")
    void decorate_capturesMdcAtSubmitTime_visibleOnWorkerThread() throws InterruptedException {
        MDC.put("correlationId", "abc-123");

        final AtomicReference<String> seenOnWorker = new AtomicReference<>();
        final Runnable decorated = decorator.decorate(() -> seenOnWorker.set(MDC.get("correlationId")));

        MDC.remove("correlationId");

        final Thread worker = new Thread(decorated);
        worker.start();
        worker.join();

        assertThat(seenOnWorker.get()).isEqualTo("abc-123");
    }

    @Test
    @DisplayName("MDC is cleared after the decorated runnable completes, so a pooled thread doesn't leak context")
    void decorate_clearsMdcAfterRun_noLeakageBetweenPooledTasks() throws InterruptedException {
        MDC.put("correlationId", "run-1");
        final Runnable decorated = decorator.decorate(() -> { });

        final AtomicReference<Map<String, String>> afterRun = new AtomicReference<>();
        final Thread worker = new Thread(() -> {
            decorated.run();
            afterRun.set(MDC.getCopyOfContextMap());
        });
        worker.start();
        worker.join();

        assertThat(afterRun.get()).isNullOrEmpty();
    }

    @Test
    @DisplayName("a null context map at decorate-time (no MDC set) is handled without throwing")
    void decorate_nullContextMap_isHandled() throws InterruptedException {
        MDC.clear();
        final Runnable decorated = decorator.decorate(() -> { });

        final Thread worker = new Thread(decorated);
        assertThatCode(() -> {
            worker.start();
            worker.join();
        }).doesNotThrowAnyException();
    }
}
