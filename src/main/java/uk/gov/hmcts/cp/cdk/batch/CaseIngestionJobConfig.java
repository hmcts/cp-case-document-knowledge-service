package uk.gov.hmcts.cp.cdk.batch;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.util.UriComponentsBuilder;
import uk.gov.hmcts.cp.cdk.clients.hearing.HearingClient;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummariesInfo;
import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.LatestMaterialInfo;
import uk.gov.hmcts.cp.cdk.domain.Query;
import uk.gov.hmcts.cp.cdk.domain.hearing.HearingSummaries;
import uk.gov.hmcts.cp.cdk.domain.hearing.ProsecutionCaseSummaries;
import uk.gov.hmcts.cp.cdk.query.QueryClient;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.repo.QueryRepository;
import uk.gov.hmcts.cp.cdk.storage.StorageService;

import java.io.InputStream;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Configuration
@Deprecated
public class CaseIngestionJobConfig {

    public static final String JOB_NAME = "caseIngestionJob";

    private static final String CONTEXT_KEY_CASE_IDS = "caseIds";
    private static final String CONTEXT_KEY_ELIGIBLE_MATERIAL_IDS = "eligibleMaterialIds";
    private static final String EXIT_CODE_RETRY = "RETRY";
    private static final String BLOB_TEMPLATE = "cases/%s/idpc.pdf";


    private static final String INSERT_ANSWER_TASK_SQL = """
            INSERT INTO answer_tasks(case_id, query_id, status, created_at)
            VALUES (:case_id, :query_id, 'NEW', now())
            ON CONFLICT (case_id, query_id) DO NOTHING
            """;

    private static List<String> getStringListFromContext(final StepContribution contribution, final String key) {
        final Object contextValue =
                contribution.getStepExecution().getJobExecution().getExecutionContext().get(key);

        final List<String> result;
        if (contextValue instanceof List<?>) {
            @SuppressWarnings("unchecked") final List<String> list = (List<String>) contextValue;
            result = list;
        } else {
            result = List.of();
        }
        return result;
    }

    private static String buildIdpcBlobPath(final UUID caseId) {
        return BLOB_TEMPLATE.formatted(caseId);
    }

    private static CaseDocument buildCaseDocument(final UUID caseId,
                                                  final String blobUrl,
                                                  final String contentType,
                                                  final long sizeBytes) {
        final CaseDocument caseDocument = new CaseDocument();
        caseDocument.setDocId(UUID.randomUUID());
        caseDocument.setCaseId(caseId);
        caseDocument.setBlobUri(blobUrl);
        caseDocument.setContentType(contentType);
        caseDocument.setSizeBytes(sizeBytes);
        final OffsetDateTime now = OffsetDateTime.now();
        caseDocument.setUploadedAt(now);
        caseDocument.setIngestionPhase(DocumentIngestionPhase.UPLOADED);
        caseDocument.setIngestionPhaseAt(now);
        return caseDocument;
    }

    private static List<Query> resolveQueries(final QuestionsProperties questionsProperties,
                                              final QueryRepository queryRepository) {
        final List<String> labels = questionsProperties.labels();
        final List<Query> result;
        if (labels != null && !labels.isEmpty()) {
            final List<Query> byLabels = new ArrayList<>(labels.size());
            for (final String label : labels) {
                queryRepository.findByLabelIgnoreCase(label).ifPresent(byLabels::add);
            }
            result = byLabels;
        } else {
            result = queryRepository.findAll();
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object>[] buildAnswerTaskBatchParams(final List<String> eligibleCaseIdStrings,
                                                                    final List<Query> queries) {
        final int total = eligibleCaseIdStrings.size() * queries.size();
        final Map<String, Object>[] batch = new Map[total];
        int idx = 0;
        for (final String idStr : eligibleCaseIdStrings) {
            final UUID caseId = UUID.fromString(idStr);
            for (final Query query : queries) {
                batch[idx++] = Map.of(
                        "case_id", caseId,
                        "query_id", query.getQueryId()
                );
            }
        }
        return batch;
    }

    @Bean
    public Job caseIngestionJob(final JobRepository jobRepository,
                                final Step step1FetchHearingsCasesWithSingleDefendant,
                                final Step step2FilterCaseIdpcForSingleDefendant,
                                final Step step3UploadIdpc,
                                final Step step4CheckUploadStatus,
                                final Step step5EnqueueAnswerTasks) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(step1FetchHearingsCasesWithSingleDefendant)
                .next(step2FilterCaseIdpcForSingleDefendant)
                .next(step3UploadIdpc)
                .next(step4CheckUploadStatus).on(EXIT_CODE_RETRY).stopAndRestart(step4CheckUploadStatus)
                .from(step4CheckUploadStatus).on("*").to(step5EnqueueAnswerTasks)
                .end()
                .build();
    }

    @Bean
    public Step step1FetchHearingsCasesWithSingleDefendant(final JobRepository jobRepository,
                                                           final PlatformTransactionManager transactionManager,
                                                           final HearingClient hearingClient) {
        return new StepBuilder("step1_fetch_hearings_cases", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    final String court = contribution.getStepExecution().getJobParameters().getString("court");
                    final String roomId = contribution.getStepExecution().getJobParameters().getString("roomId");
                    final LocalDate date =
                            LocalDate.parse(contribution.getStepExecution().getJobParameters().getString("date"));

                    final List<HearingSummariesInfo> summaries = hearingClient.getHearingsAndCases(court, roomId, date);
                    final List<String> caseIdStrings = new ArrayList<>(summaries.size());
                    for (final HearingSummariesInfo summary : summaries) {
                        caseIdStrings.add(summary.caseId().toString());
                    }


                    contribution.getStepExecution()
                            .getJobExecution()
                            .getExecutionContext()
                            .put(CONTEXT_KEY_CASE_IDS, caseIdStrings);

                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    public Step step2FilterCaseIdpcForSingleDefendant(final JobRepository jobRepository,
                                                      final PlatformTransactionManager transactionManager,
                                                      final ProgressionClient progressionClient) {
        return new StepBuilder("step2_check_single_defendant_idpc", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    final List<String> rawCaseIds = getStringListFromContext(contribution, CONTEXT_KEY_CASE_IDS);
                    final List<String> eligibleMaterialIds = new ArrayList<>();

                    for (final String idStr : rawCaseIds) {
                        final UUID caseId = UUID.fromString(idStr);
                        final Optional<LatestMaterialInfo> meta = progressionClient.getCourtDocuments(caseId);
                        meta.ifPresent(info -> eligibleMaterialIds.add(info.materialId()));

                    }

                    contribution.getStepExecution()
                            .getJobExecution()
                            .getExecutionContext()
                            .put(CONTEXT_KEY_ELIGIBLE_MATERIAL_IDS, eligibleMaterialIds);

                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    public Step step3UploadIdpc(final JobRepository jobRepository,
                                final PlatformTransactionManager transactionManager,
                                final ProgressionClient progressionClient,
                                final StorageService storageService,
                                final CaseDocumentRepository caseDocumentRepository) {
        return new StepBuilder("step3_upload_idpc", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    final List<String> rawEligibleIds =
                            getStringListFromContext(contribution, CONTEXT_KEY_ELIGIBLE_MATERIAL_IDS);

                    for (final String idStr : rawEligibleIds) {
                        final UUID materialID = UUID.fromString(idStr);
                        final Optional<String> downloadUrl = progressionClient.getMaterialDownloadUrl(materialID);


                        if (downloadUrl.isEmpty()) {
                            continue;
                        }
                        // this needs to be disucssed and change
                        final QueryClient.CourtDocMeta meta = new QueryClient.CourtDocMeta(true,true,downloadUrl
                                .get(),"application/pdf",0L);

                        /** need to update this code with copyurl once we have destination path
                        try (InputStream inputStream = queryClient.downloadIdpc(downloadUrl.get())) {
                            final long size = meta.sizeBytes() == null ? 0L : meta.sizeBytes();
                            final String contentType = meta.contentType();
                            final String blobPath = buildIdpcBlobPath(materialID);
                            final String blobUrl = storageService.upload(blobPath, inputStream, size, contentType);
                            final CaseDocument caseDocument =
                                    buildCaseDocument(materialID, blobUrl, contentType, size);
                            caseDocumentRepository.save(caseDocument);
                        }
                         **/
                    }
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    public Step step4CheckUploadStatus(final JobRepository jobRepository,
                                       final PlatformTransactionManager transactionManager,
                                       final StorageService storageService) {
        return new StepBuilder("step4_check_upload_status", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    final List<String> rawEligibleIds =
                            getStringListFromContext(contribution, CONTEXT_KEY_ELIGIBLE_MATERIAL_IDS);

                    boolean anyPending = false;
                    for (final String idStr : rawEligibleIds) {
                        final UUID caseId = UUID.fromString(idStr);
                        final String blobPath = buildIdpcBlobPath(caseId);
                        if (!storageService.exists(blobPath)) {
                            anyPending = true;
                            break;
                        }
                    }

                    if (anyPending) {
                        contribution.setExitStatus(new ExitStatus(EXIT_CODE_RETRY));
                    } else {
                        contribution.setExitStatus(ExitStatus.COMPLETED);
                    }
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    public Step step5EnqueueAnswerTasks(final JobRepository jobRepository,
                                        final PlatformTransactionManager transactionManager,
                                        final QuestionsProperties questionsProperties,
                                        final QueryRepository queryRepository,
                                        final NamedParameterJdbcTemplate jdbcTemplate) {
        return new StepBuilder("step5_enqueue_answer_tasks", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    final List<String> rawEligibleIds =
                            getStringListFromContext(contribution, CONTEXT_KEY_ELIGIBLE_MATERIAL_IDS);

                    if (rawEligibleIds.isEmpty()) {
                        return RepeatStatus.FINISHED;
                    }

                    final List<Query> queries = resolveQueries(questionsProperties, queryRepository);
                    if (queries.isEmpty()) {
                        return RepeatStatus.FINISHED;
                    }

                    final Map<String, Object>[] batchParams =
                            buildAnswerTaskBatchParams(rawEligibleIds, queries);

                    jdbcTemplate.batchUpdate(INSERT_ANSWER_TASK_SQL, batchParams);
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }
}
