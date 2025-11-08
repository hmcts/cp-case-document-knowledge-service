package uk.gov.hmcts.cp.cdk.controllers;


import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.cp.cdk.batch.clients.common.CQRSClientProperties;
import uk.gov.hmcts.cp.cdk.services.DocumentService;
import uk.gov.hmcts.cp.openapi.api.cdk.DocumentApi;
import uk.gov.hmcts.cp.openapi.model.cdk.GetMaterialContentUrl200Response;

import java.net.URI;
import java.util.UUID;

@RestController
@Slf4j
public class DocumentController implements DocumentApi{

    private final DocumentService service;
    private final CQRSClientProperties cqrsClientProperties;

    public DocumentController(final DocumentService service,final CQRSClientProperties cqrsClientProperties) {
        this.service = service;
        this.cqrsClientProperties =cqrsClientProperties;
    }

    @Override
    public ResponseEntity<GetMaterialContentUrl200Response> getMaterialContentUrl(final UUID docId) {
        try {
            final String headerName = cqrsClientProperties.headers().cjsCppuid();

            final ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

            if (attrs == null) {
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "No request context available with cppuid");
            }

            final HttpServletRequest req = attrs.getRequest();
            final String cppuid = req.getHeader(headerName);

            if (cppuid == null || cppuid.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Missing required header: " + headerName);
            }
            final URI responseUrl = service.getMaterialContentUrl(docId, cppuid);

            final GetMaterialContentUrl200Response payload = new GetMaterialContentUrl200Response();
            payload.setUrl(responseUrl);
            return ResponseEntity.ok(payload);
        }
        catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Exception occurred in listQueries endpoint: . caseId={}, error={}",
                    docId, e.getMessage(), e);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error while getting download url", e);
        }
    }


}
