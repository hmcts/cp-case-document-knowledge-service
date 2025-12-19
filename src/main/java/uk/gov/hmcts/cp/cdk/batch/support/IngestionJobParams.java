package uk.gov.hmcts.cp.cdk.batch.support;

import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.Params.COURT_CENTRE_ID;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.Params.CPPUID;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.Params.DATE;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.Params.ROOM_ID;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.Params.RUN_ID;

import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessRequest;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;

public final class IngestionJobParams {
    private IngestionJobParams() {
    }

    public static JobParameters build(final String cppuid,
                                      final IngestionProcessRequest request,
                                      final Clock clock) {
        Objects.requireNonNull(cppuid, "cppuid must not be null");
        Objects.requireNonNull(request, "request must not be null");

        final UUID courtCentreId = Objects.requireNonNull(request.getCourtCentreId(), "courtCentreId must not be null");
        final UUID roomId = Objects.requireNonNull(request.getRoomId(), "roomId must not be null");
        final LocalDate date = Objects.requireNonNull(request.getDate(), "date must not be null");

        return new JobParametersBuilder()
                .addLong(RUN_ID, clock.millis(), true)
                .addString(COURT_CENTRE_ID, courtCentreId.toString(), true)
                .addString(ROOM_ID, roomId.toString(), true)
                .addString(DATE, date.toString(), true)
                .addString(CPPUID, cppuid)
                .toJobParameters();
    }
}
