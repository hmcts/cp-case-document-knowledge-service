package uk.gov.hmcts.cp.cdk.batch.tasklet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_CASE_IDS_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.Params.COURT_CENTRE_ID;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.Params.CPPUID;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.Params.DATE;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.Params.ROOM_ID;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.USERID_FOR_EXTERNAL_CALLS;

import uk.gov.hmcts.cp.cdk.clients.hearing.HearingClient;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummariesInfo;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;


@ExtendWith(MockitoExtension.class)
class FetchHearingsCasesTaskletTest {

    @Mock
    private HearingClient hearingClient;

    @Mock
    private StepContribution contribution;

    @Mock
    private ChunkContext chunkContext;

    @Mock
    private StepExecution stepExecution;

    @Mock
    private JobExecution jobExecution;

    private ExecutionContext jobExecutionContext;

    private FetchHearingsCasesTasklet tasklet;

    @BeforeEach
    void setUp() {
        tasklet = new FetchHearingsCasesTasklet(hearingClient);

        jobExecutionContext = new ExecutionContext();

        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getJobExecution()).thenReturn(jobExecution);
        when(jobExecution.getExecutionContext()).thenReturn(jobExecutionContext);
    }

    @Test
    @DisplayName("Missing required params -> NOOP, empty case list, USERID propagated, no hearing call")
    void missingRequiredParams_noopAndEmptyList() throws Exception {
        final JobParameters params = new JobParametersBuilder()
                .addString(ROOM_ID, "ROOM-1")
                .addString(DATE, LocalDate.now().toString())
                .addString(CPPUID, "user-123")
                .toJobParameters();

        when(stepExecution.getJobParameters()).thenReturn(params);

        final RepeatStatus status = tasklet.execute(contribution, chunkContext);
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);

        assertThat(jobExecutionContext.getString(USERID_FOR_EXTERNAL_CALLS)).isEqualTo("user-123");
        @SuppressWarnings("unchecked") final List<String> caseIds = (List<String>) jobExecutionContext.get(CTX_CASE_IDS_KEY);
        assertThat(caseIds).isNotNull().isEmpty();

        verify(contribution).setExitStatus(ExitStatus.NOOP);
        verifyNoInteractions(hearingClient);
    }

    @Test
    @DisplayName("Blank strings for required params -> NOOP, empty case list, no hearing call")
    void blankParams_noopAndEmptyList() throws Exception {
        final JobParameters params = new JobParametersBuilder()
                .addString(COURT_CENTRE_ID, " ")
                .addString(ROOM_ID, " ")
                .addString(DATE, " ")
                .addString(CPPUID, "user-xyz")
                .toJobParameters();

        when(stepExecution.getJobParameters()).thenReturn(params);

        final RepeatStatus status = tasklet.execute(contribution, chunkContext);
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);

        assertThat(jobExecutionContext.getString(USERID_FOR_EXTERNAL_CALLS)).isEqualTo("user-xyz");
        @SuppressWarnings("unchecked") final List<String> caseIds = (List<String>) jobExecutionContext.get(CTX_CASE_IDS_KEY);
        assertThat(caseIds).isNotNull().isEmpty();

        verify(contribution).setExitStatus(ExitStatus.NOOP);
        verifyNoInteractions(hearingClient);
    }

    @Test
    @DisplayName("Invalid ISO date -> NOOP, empty case list, USERID propagated, no hearing call")
    void invalidDate_noopAndEmptyList() throws Exception {
        final JobParameters params = new JobParametersBuilder()
                .addString(COURT_CENTRE_ID, "COURT-1")
                .addString(ROOM_ID, "ROOM-9")
                .addString(DATE, "2025-13-01") // invalid month
                .addString(CPPUID, "uid-1")
                .toJobParameters();

        when(stepExecution.getJobParameters()).thenReturn(params);

        final RepeatStatus status = tasklet.execute(contribution, chunkContext);
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);

        assertThat(jobExecutionContext.getString(USERID_FOR_EXTERNAL_CALLS)).isEqualTo("uid-1");
        @SuppressWarnings("unchecked") final List<String> caseIds = (List<String>) jobExecutionContext.get(CTX_CASE_IDS_KEY);
        assertThat(caseIds).isNotNull().isEmpty();

        verify(contribution).setExitStatus(ExitStatus.NOOP);
        verifyNoInteractions(hearingClient);
    }

    @Test
    @DisplayName("Valid params, hearing service returns null -> COMPLETED with empty case list")
    void validParams_nullSummaries_completedWithEmptyList() throws Exception {
        final JobParameters params = new JobParametersBuilder()
                .addString(COURT_CENTRE_ID, "C-1")
                .addString(ROOM_ID, "R-1")
                .addString(DATE, "2025-11-10")
                .addString(CPPUID, "cpp-9")
                .toJobParameters();

        when(stepExecution.getJobParameters()).thenReturn(params);
        when(hearingClient.getHearingsAndCases("C-1", "R-1", LocalDate.parse("2025-11-10"), "cpp-9"))
                .thenReturn(null);

        final RepeatStatus status = tasklet.execute(contribution, chunkContext);
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);

        assertThat(jobExecutionContext.getString(USERID_FOR_EXTERNAL_CALLS)).isEqualTo("cpp-9");
        @SuppressWarnings("unchecked") final List<String> caseIds = (List<String>) jobExecutionContext.get(CTX_CASE_IDS_KEY);
        assertThat(caseIds).isNotNull().isEmpty();

        verify(contribution).setExitStatus(ExitStatus.COMPLETED);
        verify(hearingClient).getHearingsAndCases("C-1", "R-1", LocalDate.parse("2025-11-10"), "cpp-9");
    }

    @Test
    @DisplayName("Valid params, hearing service returns results -> COMPLETED and case IDs stored")
    void validParams_results_completedAndStored() throws Exception {
        final JobParameters params = new JobParametersBuilder()
                .addString(COURT_CENTRE_ID, "CENTRE-X")
                .addString(ROOM_ID, "ROOM-7")
                .addString(DATE, "2025-11-10")
                .addString(CPPUID, "user-777")
                .toJobParameters();

        when(stepExecution.getJobParameters()).thenReturn(params);

        final HearingSummariesInfo summary1 = org.mockito.Mockito.mock(HearingSummariesInfo.class);
        final HearingSummariesInfo summary2 = org.mockito.Mockito.mock(HearingSummariesInfo.class);
        when(summary1.caseId()).thenReturn("CASE-A");
        when(summary2.caseId()).thenReturn("CASE-B");

        when(hearingClient.getHearingsAndCases("CENTRE-X", "ROOM-7", LocalDate.parse("2025-11-10"), "user-777"))
                .thenReturn(List.of(summary1, summary2));

        final RepeatStatus status = tasklet.execute(contribution, chunkContext);
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);

        assertThat(jobExecutionContext.getString(USERID_FOR_EXTERNAL_CALLS)).isEqualTo("user-777");
        @SuppressWarnings("unchecked") final List<String> caseIds = (List<String>) jobExecutionContext.get(CTX_CASE_IDS_KEY);
        assertThat(caseIds).containsExactly("CASE-A", "CASE-B");

        verify(contribution).setExitStatus(ExitStatus.COMPLETED);
        verify(hearingClient).getHearingsAndCases("CENTRE-X", "ROOM-7", LocalDate.parse("2025-11-10"), "user-777");
    }
}
