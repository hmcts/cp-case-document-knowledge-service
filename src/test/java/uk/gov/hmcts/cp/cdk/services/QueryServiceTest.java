package uk.gov.hmcts.cp.cdk.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import uk.gov.hmcts.cp.openapi.model.cdk.QueryDefinitionsResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.QueryLifecycleStatus;
import uk.gov.hmcts.cp.openapi.model.cdk.QueryStatusResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.QuerySummary;
import uk.gov.hmcts.cp.openapi.model.cdk.QueryUpsertRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.QueryUpsertRequestQueriesInner;
import uk.gov.hmcts.cp.openapi.model.cdk.QueryVersionSummary;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@DisplayName("QueryService tests")
@ExtendWith(MockitoExtension.class)
class QueryServiceTest {

    @Mock
    private QueryRepository qRepo;
    @Mock
    private QueryVersionRepository qvRepo;
    @Mock
    private QueriesAsOfRepository asOfRepo;
    @Mock
    private CaseDocumentRepository docRepo;
    @Mock
    private QueryMapper mapper;
    @Mock
    private ProgressionClient progressionClient;

    @InjectMocks
    private QueryService service;

    @Test
    @DisplayName("listForCaseAsOf(null) returns definition snapshots")
    void list_definitions_snapshot() {
        final OffsetDateTime asOf = OffsetDateTime.parse("2025-05-01T12:00:00Z");
        final UUID qid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        final QueryVersionRepository.SnapshotDefinition row = new QueryVersionRepository.SnapshotDefinition(qid, "L", "UQ", "QP", asOf.toInstant());
        when(qvRepo.snapshotDefinitionsAsOf(asOf)).thenReturn(List.of(row));

        final QueryStatusResponse resp = service.listForCaseAsOf(null, asOf, "u-123");

        assertThat(resp.getAsOf()).isEqualTo(asOf);
        assertThat(resp.getScope()).isNull();
        assertThat(resp.getQueries()).hasSize(1);

        final QuerySummary s = resp.getQueries().get(0);
        assertThat(s.getQueryId()).isEqualTo(qid);
        assertThat(s.getLabel()).isEqualTo("L");
        assertThat(s.getUserQuery()).isEqualTo("UQ");
        assertThat(s.getQueryPrompt()).isEqualTo("QP");
        assertThat(s.getEffectiveAt()).isEqualTo(asOf);
    }

    @Test
    @DisplayName("listForCaseAsOf(caseId) returns case scope with IDPC available true")
    void list_case_scope_idpc_true() {
        final UUID caseId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        final UUID qid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        final OffsetDateTime eff = OffsetDateTime.parse("2025-05-01T12:00:00Z");

        final QueriesAsOfRepository.QueryAsOfView queryAsOfViewRow = new QueriesAsOfRepository.QueryAsOfView(qid, caseId, "L", "UQ", "QP", eff.toInstant(), "ANSWER_AVAILABLE", OffsetDateTime.now().toInstant(), 2);
        when(asOfRepo.listForCaseAsOf(eq(caseId), any())).thenReturn(List.of(queryAsOfViewRow));

        final CaseDocument doc = new CaseDocument();
        doc.setSource("IDPC");

        final LatestMaterialInfo info = new LatestMaterialInfo(
                List.of(caseId.toString()),
                "DOC_TYPE_1",
                "Some Document",
                "MAT001",
                "Material Name",
                ZonedDateTime.now()
        );
        when(progressionClient.getCourtDocuments(any(), anyString()))
                .thenReturn(Optional.of(info));

        final QueryStatusResponse resp = service.listForCaseAsOf(caseId, eff, "u-123");

        assertThat(resp.getScope().getCaseId()).isEqualTo(caseId);
        assertThat(resp.getScope().getIsIdpcAvailable()).isTrue();
        assertThat(resp.getQueries()).hasSize(1);
        assertThat(resp.getQueries().get(0).getStatus()).isEqualTo(QueryLifecycleStatus.ANSWER_AVAILABLE);
    }

    @Test
    @DisplayName("listForCaseAsOf(caseId) returns case scope with IDPC available false when no doc")
    void list_case_scope_idpc_false() {
        final UUID caseId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        final UUID qid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        final OffsetDateTime eff = OffsetDateTime.parse("2025-05-01T12:00:00Z");

        final QueriesAsOfRepository.QueryAsOfView queryAsOfViewRow = new QueriesAsOfRepository.QueryAsOfView(qid, caseId, "L", "UQ", "QP", eff.toInstant(), null, OffsetDateTime.now().toInstant(), 2);
        when(asOfRepo.listForCaseAsOf(eq(caseId), any())).thenReturn(List.of(queryAsOfViewRow));

        final QueryStatusResponse resp = service.listForCaseAsOf(caseId, eff, "u-123");

        assertThat(resp.getScope().getIsIdpcAvailable()).isFalse();
        assertThat(resp.getQueries().get(0).getStatus()).isEqualTo(QueryLifecycleStatus.ANSWER_NOT_AVAILABLE);
    }

    @Test
    @DisplayName("getOneForCaseAsOf returns mapped summary")
    void get_one_success() {
        final UUID caseId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        final UUID qid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        final OffsetDateTime eff = OffsetDateTime.parse("2025-05-01T12:00:00Z");

        final QueriesAsOfRepository.QueryAsOfView queryAsOfViewRow = new QueriesAsOfRepository.QueryAsOfView(qid, caseId, "L", "UQ", "QP", eff.toInstant(), "ANSWER_AVAILABLE", OffsetDateTime.now().toInstant(), 2);
        when(asOfRepo.getOneForCaseAsOf(caseId, qid, eff)).thenReturn(queryAsOfViewRow);

        final QuerySummary s = service.getOneForCaseAsOf(caseId, qid, eff);

        assertThat(s.getQueryId()).isEqualTo(qid);
        assertThat(s.getCaseId()).isEqualTo(caseId);
        assertThat(s.getStatus()).isEqualTo(QueryLifecycleStatus.ANSWER_AVAILABLE);
        assertThat(s.getEffectiveAt()).isEqualTo(eff);
    }

    @Test
    @DisplayName("getOneForCaseAsOf returns 404 when null row")
    void get_one_not_found_null() {
        final UUID caseId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        final UUID qid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        final OffsetDateTime eff = OffsetDateTime.parse("2025-05-01T12:00:00Z");

        when(asOfRepo.getOneForCaseAsOf(caseId, qid, eff)).thenReturn(null);

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getOneForCaseAsOf(caseId, qid, eff));

        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    @DisplayName("getOneForCaseAsOf returns 404 on incorrect result size")
    void get_one_not_found_incorrect_size() {
        final UUID caseId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        final UUID qid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        final OffsetDateTime eff = OffsetDateTime.parse("2025-05-01T12:00:00Z");

        when(asOfRepo.getOneForCaseAsOf(caseId, qid, eff))
                .thenThrow(new IncorrectResultSizeDataAccessException(1));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getOneForCaseAsOf(caseId, qid, eff));
        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    @DisplayName("upsertDefinitions success")
    void upsert_success() {
        final UUID qid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        final Query q = new Query();
        when(qRepo.findById(qid)).thenReturn(Optional.of(q));

        final OffsetDateTime eff = OffsetDateTime.parse("2025-05-01T12:00:00Z");
        final QueryVersionRepository.SnapshotDefinition row = new QueryVersionRepository.SnapshotDefinition(qid, "L", "UQ", "QP", eff.toInstant());
        when(qvRepo.snapshotDefinitionsAsOf(eff)).thenReturn(List.of(row));

        final QueryUpsertRequest req = new QueryUpsertRequest();
        final QueryUpsertRequestQueriesInner item = new QueryUpsertRequestQueriesInner();
        item.setQueryId(qid);
        item.setUserQuery("UQ");
        item.setQueryPrompt("QP");
        req.setEffectiveAt(eff);
        req.setQueries(List.of(item));

        final QueryDefinitionsResponse resp = service.upsertDefinitions(req);

        final ArgumentCaptor<QueryVersion> cap = ArgumentCaptor.forClass(QueryVersion.class);
        verify(qvRepo, times(1)).save(cap.capture());

        final QueryVersion saved = cap.getValue();
        assertThat(saved.getQuery()).isEqualTo(q);

        final QueryVersionId id = saved.getQueryVersionId();
        assertThat(id.getQueryId()).isEqualTo(qid);
        assertThat(id.getEffectiveAt()).isEqualTo(eff);

        assertThat(resp.getAsOf()).isEqualTo(eff);
        assertThat(resp.getQueries()).hasSize(1);
        assertThat(resp.getQueries().get(0).getQueryId()).isEqualTo(qid);
    }

    @Test
    @DisplayName("upsertDefinitions uses server time when effectiveAt null")
    void upsert_uses_server_time_when_null_effectiveAt() {
        final UUID qid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        when(qRepo.findById(qid)).thenReturn(Optional.of(new Query()));

        final QueryUpsertRequest req = new QueryUpsertRequest();
        final QueryUpsertRequestQueriesInner item = new QueryUpsertRequestQueriesInner();
        item.setQueryId(qid);
        item.setUserQuery("UQ");
        item.setQueryPrompt("QP");
        req.setQueries(List.of(item));

        service.upsertDefinitions(req);

        final ArgumentCaptor<QueryVersion> cap = ArgumentCaptor.forClass(QueryVersion.class);
        verify(qvRepo).save(cap.capture());
        assertThat(cap.getValue().getQueryVersionId().getEffectiveAt()).isNotNull();
    }

    @Test
    @DisplayName("upsertDefinitions 400 when empty")
    void upsert_empty_400() {
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upsertDefinitions(new QueryUpsertRequest()));
        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    @DisplayName("upsertDefinitions 404 when unknown queryId")
    void upsert_unknown_query_404() {
        when(qRepo.findById(any())).thenReturn(Optional.empty());

        final QueryUpsertRequest req = new QueryUpsertRequest();
        final QueryUpsertRequestQueriesInner item = new QueryUpsertRequestQueriesInner();
        item.setQueryId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
        item.setUserQuery("UQ");
        item.setQueryPrompt("QP");
        req.setQueries(List.of(item));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upsertDefinitions(req));
        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    @DisplayName("upsertDefinitions 400 when userQuery blank")
    void upsert_userQuery_blank_400() {
        final UUID qid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        when(qRepo.findById(qid)).thenReturn(Optional.of(new Query()));

        final QueryUpsertRequest req = new QueryUpsertRequest();
        final QueryUpsertRequestQueriesInner item = new QueryUpsertRequestQueriesInner();
        item.setQueryId(qid);
        item.setUserQuery(" ");
        item.setQueryPrompt("QP");
        req.setQueries(List.of(item));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upsertDefinitions(req));
        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    @DisplayName("upsertDefinitions 400 when queryPrompt blank")
    void upsert_queryPrompt_blank_400() {
        final UUID qid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        when(qRepo.findById(qid)).thenReturn(Optional.of(new Query()));

        final QueryUpsertRequest req = new QueryUpsertRequest();
        final QueryUpsertRequestQueriesInner item = new QueryUpsertRequestQueriesInner();
        item.setQueryId(qid);
        item.setUserQuery("UQ");
        item.setQueryPrompt("");
        req.setQueries(List.of(item));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upsertDefinitions(req));
        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    @DisplayName("listVersions returns mapped versions")
    void list_versions_success() {
        final UUID qid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        final Query query = new Query();
        when(qRepo.findById(qid)).thenReturn(Optional.of(query));

        final QueryVersion v = new QueryVersion();
        when(qvRepo.findAllVersions(qid)).thenReturn(List.of(v));

        final QueryVersionSummary vs = new QueryVersionSummary();
        vs.setQueryId(qid);
        vs.setLabel("L");
        vs.setUserQuery("UQ");
        vs.setQueryPrompt("QP");
        vs.setEffectiveAt(OffsetDateTime.parse("2025-05-01T12:00:00Z"));
        when(mapper.toVersionSummary(query, v)).thenReturn(vs);

        final List<QueryVersionSummary> out = service.listVersions(qid);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getQueryId()).isEqualTo(qid);
        assertThat(out.get(0).getLabel()).isEqualTo("L");
    }

    @Test
    @DisplayName("listVersions 404 when query not found")
    void list_versions_not_found() {
        when(qRepo.findById(any())).thenReturn(Optional.empty());

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.listVersions(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")));
        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }
}
