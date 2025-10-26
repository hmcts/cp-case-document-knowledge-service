package uk.gov.hmcts.cp.cdk.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnswerTaskProcessorJobConfigTest {

    private JobRepository jobRepository;
    private PlatformTransactionManager transactionManager;
    private NamedParameterJdbcTemplate jdbc;
    private JdbcTemplate jdbcTemplate;
    private Step stepUnderTest;

    @BeforeEach
    void setUp() {
        jobRepository = mock(JobRepository.class);
        jdbc = mock(NamedParameterJdbcTemplate.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbc.getJdbcTemplate()).thenReturn(jdbcTemplate);
        transactionManager = new ResourcelessTransactionManager();
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
        AnswerTaskProcessorJobConfig config = new AnswerTaskProcessorJobConfig();
        stepUnderTest = config.processStep(jobRepository, transactionManager, jdbc);
    }

    @Test
    @DisplayName("Finishes immediately when no tasks are claimed")
    void finishesWhenNoClaims() throws Exception {
        when(jdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<Map<String, Object>>>any()))
                .thenReturn(Collections.emptyList());
        StepExecution se = newStepExecution();
        stepUnderTest.execute(se);
        assertThat(se.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
        verify(jdbc, never()).update(anyString(), anyMap());
    }

    @Test
    @DisplayName("Marks claimed tasks as DONE, then completes when no more work")
    void claimsThenMarksDoneThenFinishes() throws Exception {
        UUID c1 = UUID.randomUUID(), q1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID(), q2 = UUID.randomUUID();

        Map<String, Object> row1 = new HashMap<>();
        row1.put("case_id", c1);
        row1.put("query_id", q1);

        Map<String, Object> row2 = new HashMap<>();
        row2.put("case_id", c2);
        row2.put("query_id", q2);

        when(jdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<Map<String, Object>>>any()))
                .thenReturn(Arrays.asList(row1, row2))
                .thenReturn(Collections.emptyList());
        when(jdbc.update(anyString(), ArgumentMatchers.<Map<String, Object>>any())).thenReturn(1);

        StepExecution se = newStepExecution();
        stepUnderTest.execute(se);

        assertThat(se.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> mapCaptor =
                (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Map.class);

        verify(jdbc, times(2)).update(sqlCaptor.capture(), mapCaptor.capture());

        List<String> sqls = sqlCaptor.getAllValues();
        List<Map<String, Object>> maps = mapCaptor.getAllValues();

        assertThat(sqls).allMatch(s -> s.contains("UPDATE answer_tasks SET status='DONE'"));
        assertThat(maps).containsExactly(row1, row2);
        assertThat(maps.get(0)).containsEntry("case_id", c1).containsEntry("query_id", q1);
        assertThat(maps.get(1)).containsEntry("case_id", c2).containsEntry("query_id", q2);
    }

    private StepExecution newStepExecution() {
        JobInstance jobInstance = new JobInstance(1L, AnswerTaskProcessorJobConfig.JOB_NAME);
        JobParameters params = new JobParameters();
        JobExecution jobExecution = new JobExecution(jobInstance, params);
        StepExecution se = new StepExecution(AnswerTaskProcessorJobConfig.STEP_NAME, jobExecution);
        jobRepository.add(se);
        return se;
    }
}
