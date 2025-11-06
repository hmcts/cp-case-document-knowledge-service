package uk.gov.hmcts.cp.cdk.batch.tasklet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import uk.gov.hmcts.cp.cdk.batch.BatchKeys;
import uk.gov.hmcts.cp.cdk.batch.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.batch.clients.progression.dto.LatestMaterialInfo;
import uk.gov.hmcts.cp.cdk.batch.clients.progression.dto.MaterialMetaData;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
@DisplayName("Filter Eligible Cases Tasklet tests")

class FilterEligibleCasesTaskletTest {

    private JobRepository jobRepository;
    private ProgressionClient progressionClient;
    private FilterEligibleCasesTasklet tasklet;

    @BeforeEach
    void setUp() {
        jobRepository = mock(JobRepository.class);
        progressionClient = mock(ProgressionClient.class);
        tasklet = new FilterEligibleCasesTasklet(progressionClient);


        doAnswer(inv -> {
            StepExecution s = inv.getArgument(0);
            if (s.getJobExecution() != null && s.getJobExecution().getId() == null) {
                s.getJobExecution().setId(1L);
            }
            if (s.getId() == null) {
                s.setId(1L);
            }
            return null;
        }).when(jobRepository).add(any(StepExecution.class));
    }

    @Test
    @DisplayName("FilterEligibleCasesTasklet filters and stores eligible materials and  mapping")
    void filtersAndStoresEligibleCases() throws Exception {
        // Arrange
        UUID case1 = UUID.randomUUID();
        UUID case2 = UUID.randomUUID();
        UUID material1 = UUID.randomUUID();
        List<String> caseIds = new ArrayList<String>();
        caseIds.add(case1.toString());
        // Only first case has document
        LatestMaterialInfo meta1 = new LatestMaterialInfo(caseIds, "application/pdf", "123L",material1.toString(),"IDPC",null);
        when(progressionClient.getCourtDocuments(case1,"userId")).thenReturn(Optional.of(meta1));
        when(progressionClient.getCourtDocuments(case2,"userId")).thenReturn(Optional.empty());

        JobParameters params = new JobParametersBuilder()
                .addString("jobName", "filterEligibleCases")
                .addString("cppuid","userId")
                .toJobParameters();

        StepExecution stepExecution = newStepExecution("filter_eligible_cases", params);
        ExecutionContext ctx = stepExecution.getJobExecution().getExecutionContext();
        ctx.put(BatchKeys.CTX_CASE_IDS_KEY, List.of(case1.toString(), case2.toString()));
        ctx.put(BatchKeys.USERID_FOR_EXTERNAL_CALLS,"userId");
        StepContribution contribution = new StepContribution(stepExecution);
        ChunkContext chunkContext = new ChunkContext(new StepContext(stepExecution));

        // Act
        RepeatStatus status = tasklet.execute(contribution, chunkContext);

        // Assert

        @SuppressWarnings("unchecked")
        Map<String, MaterialMetaData> materialToCaseMap = (Map<String, MaterialMetaData>) ctx.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        assertThat(materialToCaseMap).containsKey(case1.toString());

        verify(progressionClient, times(1)).getCourtDocuments(case1,"userId");
        verify(progressionClient, times(1)).getCourtDocuments(case2,"userId");
    }


    private StepExecution newStepExecution(final String stepName, final JobParameters params) {
        JobInstance jobInstance = new JobInstance(1L, "filterEligibleCasesJob");
        JobExecution jobExecution = new JobExecution(jobInstance, params);
        StepExecution stepExecution = new StepExecution(stepName, jobExecution);
        jobRepository.add(stepExecution);
        return stepExecution;
    }
}
