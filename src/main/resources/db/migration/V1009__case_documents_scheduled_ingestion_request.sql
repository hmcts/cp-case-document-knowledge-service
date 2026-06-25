CREATE TABLE scheduled_ingestion_request (

    id UUID PRIMARY KEY,

    cppuid UUID NOT NULL,

    court_centre_id UUID NOT NULL,

    court_room_id UUID NOT NULL,

    hearing_date DATE NOT NULL,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

ALTER TABLE scheduled_ingestion_request
    ADD CONSTRAINT uq_sir_business_key
    UNIQUE (
        court_centre_id,
        court_room_id,
        hearing_date
    );

CREATE INDEX idx_sir_hearing_date
    ON scheduled_ingestion_request (hearing_date);