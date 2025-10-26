package uk.gov.hmcts.cp.cdk.batch;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Configuration
public class AnswerTaskProcessorJobConfig {

    public static final String JOB_NAME = "answerTasksProcessorJob";
    public static final String STEP_NAME = "task_processor";

    private static final String CLAIM_SQL = """
            WITH cte AS (
              SELECT case_id, query_id
              FROM answer_tasks
              WHERE status='NEW'
              ORDER BY created_at
              FOR UPDATE SKIP LOCKED
              LIMIT 50
            )
            UPDATE answer_tasks t
            SET status='IN_PROGRESS', updated_at=now()
            FROM cte
            WHERE t.case_id = cte.case_id AND t.query_id = cte.query_id
            RETURNING t.case_id, t.query_id
            """;

    private static final String MARK_DONE_SQL =
            "UPDATE answer_tasks SET status='DONE', updated_at=now() " +
                    "WHERE case_id=:case_id AND query_id=:query_id";

    @Bean
    public Job answerTasksProcessorJob(final JobRepository jobRepository, final Step processStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(processStep)
                .build();
    }

    @Bean
    public Step processStep(final JobRepository jobRepository,
                            final PlatformTransactionManager transactionManager,
                            final NamedParameterJdbcTemplate jdbc) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .tasklet((contribution, ctx) -> {
                    final List<Map<String, Object>> claimed = jdbc.getJdbcTemplate()
                            .query(CLAIM_SQL, (rs, rowNum) -> {
                                final Map<String, Object> row = new HashMap<>(2);
                                row.put("case_id", UUID.fromString(rs.getString("case_id")));
                                row.put("query_id", UUID.fromString(rs.getString("query_id")));
                                return row;
                            });

                    if (claimed.isEmpty()) {
                        return RepeatStatus.FINISHED;
                    }

                    for (final Map<String, Object> row : claimed) {
                        jdbc.update(MARK_DONE_SQL, row);
                    }

                    return RepeatStatus.CONTINUABLE;
                }, transactionManager)
                .build();
    }
}
