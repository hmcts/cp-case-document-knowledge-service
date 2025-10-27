package uk.gov.hmcts.cp.cdk.controllers;


import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.cdk.domain.HearingSummariesListRequest;
import uk.gov.hmcts.cp.cdk.domain.ProsecutionCaseSummaries;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class HearingSummariesController {

    @PostMapping("/filter-single-defendant")
    public ResponseEntity<List<String>> filterSingleDefendant(@RequestBody HearingSummariesListRequest request) {
        if (request == null || request.getHearingSummaries() == null) {
            return ResponseEntity.badRequest().build();
        }

        List<String> resultIds = request.getHearingSummaries().stream()
                // for each HearingSummaries
                .flatMap(hearingSummaries -> {
                    if (hearingSummaries.getProsecutionCaseSummaries() == null) {
                        return java.util.stream.Stream.empty();
                    }
                    return hearingSummaries.getProsecutionCaseSummaries().stream()
                            // filter ProsecutionCaseSummaries objects where person list size == 1
                            .filter(prosecutionCaseSummaries -> prosecutionCaseSummaries.getDefendants() != null && prosecutionCaseSummaries.getDefendants().size() == 1)
                            // map to id
                            .map(ProsecutionCaseSummaries::getId);
                })
                .distinct() // optional: ensure unique ids
                .collect(Collectors.toList());

        return ResponseEntity.ok(resultIds);
    }
}
