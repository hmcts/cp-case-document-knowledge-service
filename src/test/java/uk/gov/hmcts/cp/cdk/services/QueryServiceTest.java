package uk.gov.hmcts.cp.cdk.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.cp.cdk.batch.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.batch.clients.progression.dto.LatestMaterialInfo;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.Query;
import uk.gov.hmcts.cp.cdk.domain.QueryVersion;
import uk.gov.hmcts.cp.cdk.domain.QueryVersionId;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.repo.QueriesAsOfRepository;
import uk.gov.hmcts.cp.cdk.repo.QueryRepository;
import uk.gov.hmcts.cp.cdk.repo.QueryVersionRepository;
import uk.gov.hmcts.cp.cdk.services.mapper.QueryMapper;
import uk.gov.hmcts.cp.openapi.model.cdk.*;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("QueryService tests")
class QueryServiceTest {

    private QueryService svc(final QueryRepository qRepo,
                             final QueryVersionRepository qvRepo,
                             final QueriesAsOfRepository asOfRepo,
                             final CaseDocumentRepository docRepo,
                             final QueryMapper mapper,
                             final ProgressionClient progressionClient) {
        return new QueryService(qRepo, qvRepo, asOfRepo, docRepo, mapper,progressionClient);
    }

    @Test
    @DisplayName("listForCaseAsOf(null) returns definition snapshots")
    void list_definitions_snapshot() {
        QueryRepository qRepo = mock(QueryRepository.class);
        QueryVersionRepository qvRepo = mock(QueryVersionRepository.class);
        QueriesAsOfRepository asOfRepo = mock(QueriesAsOfRepository.class);
        CaseDocumentRepository docRepo = mock(CaseDocumentRepository.class);
        QueryMapper mapper = mock(QueryMapper.class);
        ProgressionClient progressionClient = mock(ProgressionClient.class);
        OffsetDateTime asOf = OffsetDateTime.parse("2025-05-01T12:00:00Z");
        UUID qid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        Object[] row = new Object[]{qid, "L", "UQ", "QP", asOf};
        when(qvRepo.snapshotDefinitionsAsOf(asOf)).thenReturn(List.<Object[]>of(row));

        QueryService service = svc(qRepo, qvRepo, asOfRepo, docRepo, mapper,progressionClient);

        QueryStatusResponse resp = service.listForCaseAsOf(null, asOf,"u-123");

        assertThat(resp.getAsOf()).isEqualTo(asOf);
        assertThat(resp.getScope()).isNull();
        assertThat(resp.getQueries()).hasSize(1);
        QuerySummary s = resp.getQueries().get(0);
        assertThat(s.getQueryId()).isEqualTo(qid);
        assertThat(s.getLabel()).isEqualTo("L");
        assertThat(s.getUserQuery()).isEqualTo("UQ");
        assertThat(s.getQueryPrompt()).isEqualTo("QP");
        assertThat(s.getEffectiveAt()).isEqualTo(asOf);
    }

    @Test
    @DisplayName("listForCaseAsOf(caseId) returns case scope with IDPC available true")
    void list_case_scope_idpc_true() {
        QueryRepository qRepo = mock(QueryRepository.class);
        QueryVersionRepository qvRepo = mock(QueryVersionRepository.class);
        QueriesAsOfRepository asOfRepo = mock(QueriesAsOfRepository.class);
        CaseDocumentRepository docRepo = mock(CaseDocumentRepository.class);
        QueryMapper mapper = mock(QueryMapper.class);
        ProgressionClient progressionClient = mock(ProgressionClient.class);

        UUID caseId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID qid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        OffsetDateTime eff = OffsetDateTime.parse("2025-05-01T12:00:00Z");

        Object[] row = new Object[]{qid, caseId, "L", "UQ", "QP", eff, "ANSWER_AVAILABLE"};
        when(asOfRepo.listForCaseAsOf(eq(caseId), any())).thenReturn(List.<Object[]>of(row));

        CaseDocument doc = new CaseDocument();
        doc.setSource("IDPC");
        when(docRepo.findFirstByCaseIdOrderByUploadedAtDesc(caseId)).thenReturn(Optional.of(doc));

        LatestMaterialInfo info = new LatestMaterialInfo(
                List.of(caseId.toString()),
                "DOC_TYPE_1",
                "Some Document",
                "MAT001",
                "Material Name",
                ZonedDateTime.now()
        );
        when(progressionClient.getCourtDocuments(any(), anyString()))
                .thenReturn(Optional.of(info));

        QueryService service = svc(qRepo, qvRepo, asOfRepo, docRepo, mapper,progressionClient);

        QueryStatusResponse resp = service.listForCaseAsOf(caseId, eff,"u-123");

        assertThat(resp.getScope().getCaseId()).isEqualTo(caseId);
        assertThat(resp.getScope().getIsIdpcAvailable()).isTrue();
        assertThat(resp.getQueries()).hasSize(1);
        assertThat(resp.getQueries().get(0).getStatus()).isEqualTo(QueryLifecycleStatus.ANSWER_AVAILABLE);
    }

    @Test
    @DisplayName("listForCaseAsOf(caseId) returns case scope with IDPC available false when no doc")
    void list_case_scope_idpc_false() {
        QueryRepository qRepo = mock(QueryRepository.class);
        QueryVersionRepository qvRepo = mock(QueryVersionRepository.class);
        QueriesAsOfRepository asOfRepo = mock(QueriesAsOfRepository.class);
        CaseDocumentRepository docRepo = mock(CaseDocumentRepository.class);
        QueryMapper mapper = mock(QueryMapper.class);
        ProgressionClient progressionClient = mock(ProgressionClient.class);

        UUID caseId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID qid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        OffsetDateTime eff = OffsetDateTime.parse("2025-05-01T12:00:00Z");

        Object[] row = new Object[]{qid, caseId, "L", "UQ", "QP", eff, null};
        when(asOfRepo.listForCaseAsOf(eq(caseId), any())).thenReturn(List.<Object[]>of(row));
        when(docRepo.findFirstByCaseIdOrderByUploadedAtDesc(caseId)).thenReturn(Optional.empty());

        QueryService service = svc(qRepo, qvRepo, asOfRepo, docRepo, mapper,progressionClient);

        QueryStatusResponse resp = service.listForCaseAsOf(caseId, eff,"u-123");

        assertThat(resp.getScope().getIsIdpcAvailable()).isFalse();
        assertThat(resp.getQueries().get(0).getStatus()).isEqualTo(QueryLifecycleStatus.ANSWER_NOT_AVAILABLE);
    }

    @Test
    @DisplayName("getOneForCaseAsOf returns mapped summary")
    void get_one_success() {
        QueryRepository qRepo = mock(QueryRepository.class);
        QueryVersionRepository qvRepo = mock(QueryVersionRepository.class);
        QueriesAsOfRepository asOfRepo = mock(QueriesAsOfRepository.class);
        CaseDocumentRepository docRepo = mock(CaseDocumentRepository.class);
        QueryMapper mapper = mock(QueryMapper.class);
        ProgressionClient progressionClient = mock(ProgressionClient.class);

        UUID caseId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID qid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        OffsetDateTime eff = OffsetDateTime.parse("2025-05-01T12:00:00Z");

        Object[] row = new Object[]{qid, caseId, "L", "UQ", "QP", eff, "ANSWER_AVAILABLE"};
        when(asOfRepo.getOneForCaseAsOf(caseId, qid, eff)).thenReturn(row);

        QueryService service = svc(qRepo, qvRepo, asOfRepo, docRepo, mapper,progressionClient);

        QuerySummary s = service.getOneForCaseAsOf(caseId, qid, eff);

        assertThat(s.getQueryId()).isEqualTo(qid);
        assertThat(s.getCaseId()).isEqualTo(caseId);
        assertThat(s.getStatus()).isEqualTo(QueryLifecycleStatus.ANSWER_AVAILABLE);
        assertThat(s.getEffectiveAt()).isEqualTo(eff);
    }

    @Test
    @DisplayName("getOneForCaseAsOf returns 404 when null row")
    void get_one_not_found_null() {
        QueryRepository qRepo = mock(QueryRepository.class);
        QueryVersionRepository qvRepo = mock(QueryVersionRepository.class);
        QueriesAsOfRepository asOfRepo = mock(QueriesAsOfRepository.class);
        CaseDocumentRepository docRepo = mock(CaseDocumentRepository.class);
        QueryMapper mapper = mock(QueryMapper.class);
        ProgressionClient progressionClient = mock(ProgressionClient.class);

        UUID caseId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID qid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        OffsetDateTime eff = OffsetDateTime.parse("2025-05-01T12:00:00Z");

        when(asOfRepo.getOneForCaseAsOf(caseId, qid, eff)).thenReturn(null);

        QueryService service = svc(qRepo, qvRepo, asOfRepo, docRepo, mapper,progressionClient);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getOneForCaseAsOf(caseId, qid, eff));
        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    @DisplayName("getOneForCaseAsOf returns 404 on incorrect result size")
    void get_one_not_found_incorrect_size() {
        QueryRepository qRepo = mock(QueryRepository.class);
        QueryVersionRepository qvRepo = mock(QueryVersionRepository.class);
        QueriesAsOfRepository asOfRepo = mock(QueriesAsOfRepository.class);
        CaseDocumentRepository docRepo = mock(CaseDocumentRepository.class);
        QueryMapper mapper = mock(QueryMapper.class);
        ProgressionClient progressionClient = mock(ProgressionClient.class);
        UUID caseId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID qid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        OffsetDateTime eff = OffsetDateTime.parse("2025-05-01T12:00:00Z");

        when(asOfRepo.getOneForCaseAsOf(caseId, qid, eff))
                .thenThrow(new IncorrectResultSizeDataAccessException(1));

        QueryService service = svc(qRepo, qvRepo, asOfRepo, docRepo, mapper,progressionClient);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getOneForCaseAsOf(caseId, qid, eff));
        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    @DisplayName("upsertDefinitions success")
    void upsert_success() {
        QueryRepository qRepo = mock(QueryRepository.class);
        QueryVersionRepository qvRepo = mock(QueryVersionRepository.class);
        QueriesAsOfRepository asOfRepo = mock(QueriesAsOfRepository.class);
        CaseDocumentRepository docRepo = mock(CaseDocumentRepository.class);
        QueryMapper mapper = mock(QueryMapper.class);
        ProgressionClient progressionClient = mock(ProgressionClient.class);

        UUID qid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        Query q = new Query();
        when(qRepo.findById(qid)).thenReturn(Optional.of(q));

        OffsetDateTime eff = OffsetDateTime.parse("2025-05-01T12:00:00Z");
        Object[] row = new Object[]{qid, "L", "UQ", "QP", eff};
        when(qvRepo.snapshotDefinitionsAsOf(eff)).thenReturn(List.<Object[]>of(row));

        QueryService service = svc(qRepo, qvRepo, asOfRepo, docRepo, mapper,progressionClient);

        QueryUpsertRequest req = new QueryUpsertRequest();
        QueryUpsertRequestQueriesInner item = new QueryUpsertRequestQueriesInner();
        item.setQueryId(qid);
        item.setUserQuery("UQ");
        item.setQueryPrompt("QP");
        req.setEffectiveAt(eff);
        req.setQueries(List.of(item));

        QueryDefinitionsResponse resp = service.upsertDefinitions(req);

        ArgumentCaptor<QueryVersion> cap = ArgumentCaptor.forClass(QueryVersion.class);
        verify(qvRepo, times(1)).save(cap.capture());
        QueryVersion saved = cap.getValue();
        assertThat(saved.getQuery()).isEqualTo(q);
        QueryVersionId id = saved.getQueryVersionId();
        assertThat(id.getQueryId()).isEqualTo(qid);
        assertThat(id.getEffectiveAt()).isEqualTo(eff);

        assertThat(resp.getAsOf()).isEqualTo(eff);
        assertThat(resp.getQueries()).hasSize(1);
        assertThat(resp.getQueries().get(0).getQueryId()).isEqualTo(qid);
    }

    @Test
    @DisplayName("upsertDefinitions uses server time when effectiveAt null")
    void upsert_uses_server_time_when_null_effectiveAt() {
        QueryRepository qRepo = mock(QueryRepository.class);
        QueryVersionRepository qvRepo = mock(QueryVersionRepository.class);
        QueriesAsOfRepository asOfRepo = mock(QueriesAsOfRepository.class);
        CaseDocumentRepository docRepo = mock(CaseDocumentRepository.class);
        QueryMapper mapper = mock(QueryMapper.class);
        ProgressionClient progressionClient = mock(ProgressionClient.class);

        UUID qid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        when(qRepo.findById(qid)).thenReturn(Optional.of(new Query()));

        QueryService service = svc(qRepo, qvRepo, asOfRepo, docRepo, mapper,progressionClient);

        QueryUpsertRequest req = new QueryUpsertRequest();
        QueryUpsertRequestQueriesInner item = new QueryUpsertRequestQueriesInner();
        item.setQueryId(qid);
        item.setUserQuery("UQ");
        item.setQueryPrompt("QP");
        req.setQueries(List.of(item));

        service.upsertDefinitions(req);

        ArgumentCaptor<QueryVersion> cap = ArgumentCaptor.forClass(QueryVersion.class);
        verify(qvRepo).save(cap.capture());
        assertThat(cap.getValue().getQueryVersionId().getEffectiveAt()).isNotNull();
    }

    @Test
    @DisplayName("upsertDefinitions 400 when empty")
    void upsert_empty_400() {
        QueryRepository qRepo = mock(QueryRepository.class);
        QueryVersionRepository qvRepo = mock(QueryVersionRepository.class);
        QueriesAsOfRepository asOfRepo = mock(QueriesAsOfRepository.class);
        CaseDocumentRepository docRepo = mock(CaseDocumentRepository.class);
        QueryMapper mapper = mock(QueryMapper.class);
        ProgressionClient progressionClient = mock(ProgressionClient.class);

        QueryService service = svc(qRepo, qvRepo, asOfRepo, docRepo, mapper,progressionClient);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upsertDefinitions(new QueryUpsertRequest()));
        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    @DisplayName("upsertDefinitions 404 when unknown queryId")
    void upsert_unknown_query_404() {
        QueryRepository qRepo = mock(QueryRepository.class);
        QueryVersionRepository qvRepo = mock(QueryVersionRepository.class);
        QueriesAsOfRepository asOfRepo = mock(QueriesAsOfRepository.class);
        CaseDocumentRepository docRepo = mock(CaseDocumentRepository.class);
        QueryMapper mapper = mock(QueryMapper.class);
        ProgressionClient progressionClient = mock(ProgressionClient.class);

        when(qRepo.findById(any())).thenReturn(Optional.empty());

        QueryService service = svc(qRepo, qvRepo, asOfRepo, docRepo, mapper,progressionClient);

        QueryUpsertRequest req = new QueryUpsertRequest();
        QueryUpsertRequestQueriesInner item = new QueryUpsertRequestQueriesInner();
        item.setQueryId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
        item.setUserQuery("UQ");
        item.setQueryPrompt("QP");
        req.setQueries(List.of(item));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upsertDefinitions(req));
        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    @DisplayName("upsertDefinitions 400 when userQuery blank")
    void upsert_userQuery_blank_400() {
        QueryRepository qRepo = mock(QueryRepository.class);
        QueryVersionRepository qvRepo = mock(QueryVersionRepository.class);
        QueriesAsOfRepository asOfRepo = mock(QueriesAsOfRepository.class);
        CaseDocumentRepository docRepo = mock(CaseDocumentRepository.class);
        QueryMapper mapper = mock(QueryMapper.class);
        ProgressionClient progressionClient = mock(ProgressionClient.class);

        UUID qid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        when(qRepo.findById(qid)).thenReturn(Optional.of(new Query()));

        QueryService service = svc(qRepo, qvRepo, asOfRepo, docRepo, mapper,progressionClient);

        QueryUpsertRequest req = new QueryUpsertRequest();
        QueryUpsertRequestQueriesInner item = new QueryUpsertRequestQueriesInner();
        item.setQueryId(qid);
        item.setUserQuery(" ");
        item.setQueryPrompt("QP");
        req.setQueries(List.of(item));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upsertDefinitions(req));
        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    @DisplayName("upsertDefinitions 400 when queryPrompt blank")
    void upsert_queryPrompt_blank_400() {
        QueryRepository qRepo = mock(QueryRepository.class);
        QueryVersionRepository qvRepo = mock(QueryVersionRepository.class);
        QueriesAsOfRepository asOfRepo = mock(QueriesAsOfRepository.class);
        CaseDocumentRepository docRepo = mock(CaseDocumentRepository.class);
        QueryMapper mapper = mock(QueryMapper.class);
        ProgressionClient progressionClient = mock(ProgressionClient.class);

        UUID qid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        when(qRepo.findById(qid)).thenReturn(Optional.of(new Query()));

        QueryService service = svc(qRepo, qvRepo, asOfRepo, docRepo, mapper,progressionClient);

        QueryUpsertRequest req = new QueryUpsertRequest();
        QueryUpsertRequestQueriesInner item = new QueryUpsertRequestQueriesInner();
        item.setQueryId(qid);
        item.setUserQuery("UQ");
        item.setQueryPrompt("");
        req.setQueries(List.of(item));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upsertDefinitions(req));
        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    @DisplayName("listVersions returns mapped versions")
    void list_versions_success() {
        QueryRepository qRepo = mock(QueryRepository.class);
        QueryVersionRepository qvRepo = mock(QueryVersionRepository.class);
        QueriesAsOfRepository asOfRepo = mock(QueriesAsOfRepository.class);
        CaseDocumentRepository docRepo = mock(CaseDocumentRepository.class);
        QueryMapper mapper = mock(QueryMapper.class);
        ProgressionClient progressionClient = mock(ProgressionClient.class);

        UUID qid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        Query query = new Query();
        when(qRepo.findById(qid)).thenReturn(Optional.of(query));

        QueryVersion v = new QueryVersion();
        when(qvRepo.findAllVersions(qid)).thenReturn(List.of(v));

        QueryVersionSummary vs = new QueryVersionSummary();
        vs.setQueryId(qid);
        vs.setLabel("L");
        vs.setUserQuery("UQ");
        vs.setQueryPrompt("QP");
        vs.setEffectiveAt(OffsetDateTime.parse("2025-05-01T12:00:00Z"));
        when(mapper.toVersionSummary(query, v)).thenReturn(vs);

        QueryService service = svc(qRepo, qvRepo, asOfRepo, docRepo, mapper,progressionClient);

        List<QueryVersionSummary> out = service.listVersions(qid);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getQueryId()).isEqualTo(qid);
        assertThat(out.get(0).getLabel()).isEqualTo("L");
    }

    @Test
    @DisplayName("listVersions 404 when query not found")
    void list_versions_not_found() {
        QueryRepository qRepo = mock(QueryRepository.class);
        QueryVersionRepository qvRepo = mock(QueryVersionRepository.class);
        QueriesAsOfRepository asOfRepo = mock(QueriesAsOfRepository.class);
        CaseDocumentRepository docRepo = mock(CaseDocumentRepository.class);
        QueryMapper mapper = mock(QueryMapper.class);
        ProgressionClient progressionClient = mock(ProgressionClient.class);

        when(qRepo.findById(any())).thenReturn(Optional.empty());

        QueryService service = svc(qRepo, qvRepo, asOfRepo, docRepo, mapper,progressionClient);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.listVersions(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")));
        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }
}
