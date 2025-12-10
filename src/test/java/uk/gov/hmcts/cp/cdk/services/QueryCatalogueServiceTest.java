package uk.gov.hmcts.cp.cdk.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.cdk.domain.Query;
import uk.gov.hmcts.cp.cdk.repo.QueryRepository;
import uk.gov.hmcts.cp.cdk.services.mapper.QueryMapper;
import uk.gov.hmcts.cp.openapi.model.cdk.LabelUpdateRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.QueryCatalogueItem;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class QueryCatalogueServiceTest {

    @Mock
    private QueryRepository queryRepository;
    @Mock
    private QueryMapper mapper;
    @InjectMocks
    private QueryCatalogueService service;

    @Test
    void listReturnsMappedItems() {
        final Query q1 = new Query();
        final Query q2 = new Query();
        when(queryRepository.findAll()).thenReturn(List.of(q1, q2));

        final QueryCatalogueItem item1 = new QueryCatalogueItem();
        final QueryCatalogueItem item2 = new QueryCatalogueItem();
        when(mapper.toCatalogueItem(q1)).thenReturn(item1);
        when(mapper.toCatalogueItem(q2)).thenReturn(item2);

        final List<QueryCatalogueItem> result = service.list();

        assertThat(result.size()).isEqualTo(2);
        assertThat(result.containsAll(List.of(item1, item2))).isTrue();
        verify(queryRepository).findAll();
        verify(mapper).toCatalogueItem(q1);
        verify(mapper).toCatalogueItem(q2);
    }

    @Test
    void getReturnsMappedItem() {
        final UUID queryId = UUID.randomUUID();
        final Query q = new Query();
        when(queryRepository.findById(queryId)).thenReturn(Optional.of(q));

        final QueryCatalogueItem mapped = new QueryCatalogueItem();
        when(mapper.toCatalogueItem(q)).thenReturn(mapped);

        final QueryCatalogueItem result = service.get(queryId);

        assertSame(mapped, result);
        verify(queryRepository).findById(queryId);
        verify(mapper).toCatalogueItem(q);
    }

    @Test
    void getThrowsNotFoundIfMissing() {
        final UUID queryId = UUID.randomUUID();
        when(queryRepository.findById(queryId)).thenReturn(Optional.empty());

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.get(queryId));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getReason().contains(queryId.toString())).isTrue();
        verify(queryRepository).findById(queryId);
        verifyNoInteractions(mapper);
    }

    @Test
    void updateLabelCreatesNewQuery() {
        final UUID queryId = UUID.randomUUID();
        final LabelUpdateRequest request = new LabelUpdateRequest();
        request.setLabel("New Label");
        request.setOrder(5);

        when(queryRepository.findById(queryId)).thenReturn(Optional.empty());

        final Query savedQuery = new Query();
        savedQuery.setQueryId(queryId);
        savedQuery.setLabel("New Label");
        savedQuery.setDisplayOrder(5);

        when(queryRepository.saveAndFlush(any())).thenReturn(savedQuery);

        final QueryCatalogueItem mapped = new QueryCatalogueItem();
        when(mapper.toCatalogueItem(savedQuery)).thenReturn(mapped);

        final QueryCatalogueItem result = service.updateLabel(queryId, request);

        assertSame(mapped, result);
        verify(queryRepository).findById(queryId);
        verify(queryRepository).saveAndFlush(any(Query.class));
        verify(mapper).toCatalogueItem(savedQuery);
    }

    @Test
    void updateLabelUpdatesExistingQuery() {
        final UUID queryId = UUID.randomUUID();
        final LabelUpdateRequest request = new LabelUpdateRequest();
        request.setLabel("Updated Label");
        request.setOrder(3);

        final Query existing = new Query();
        existing.setQueryId(queryId);
        existing.setLabel("Old Label");
        existing.setDisplayOrder(1);

        when(queryRepository.findById(queryId)).thenReturn(Optional.of(existing));

        final Query savedQuery = new Query();
        savedQuery.setQueryId(queryId);
        savedQuery.setLabel("Updated Label");
        savedQuery.setDisplayOrder(3);

        when(queryRepository.saveAndFlush(existing)).thenReturn(savedQuery);

        final QueryCatalogueItem mapped = new QueryCatalogueItem();
        when(mapper.toCatalogueItem(savedQuery)).thenReturn(mapped);

        final QueryCatalogueItem result = service.updateLabel(queryId, request);

        assertSame(mapped, result);
        verify(queryRepository).findById(queryId);
        verify(queryRepository).saveAndFlush(existing);
        verify(mapper).toCatalogueItem(savedQuery);
    }

    @Test
    void updateLabelThrowsBadRequest() {
        final UUID queryId = UUID.randomUUID();
        final LabelUpdateRequest nullBody = null;
        final LabelUpdateRequest blankLabel = new LabelUpdateRequest();
        blankLabel.setLabel("  ");

        final ResponseStatusException ex1 = assertThrows(ResponseStatusException.class,
                () -> service.updateLabel(queryId, nullBody));
        assertThat(ex1.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        final ResponseStatusException ex2 = assertThrows(ResponseStatusException.class,
                () -> service.updateLabel(queryId, blankLabel));
        assertThat(ex2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(queryRepository, mapper);
    }
}