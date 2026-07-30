CREATE TABLE discovery_scheduler_configuration (
    id UUID PRIMARY KEY,
    court_centre_id UUID NOT NULL,
    court_room_id UUID NOT NULL,
    uploaded_date DATE NOT NULL,
    version INTEGER NOT NULL,
    is_active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

ALTER TABLE discovery_scheduler_configuration
    ADD CONSTRAINT uq_dsc_centre_room_version
    UNIQUE (court_centre_id, court_room_id, version);

CREATE INDEX idx_dsc_centre_room ON discovery_scheduler_configuration (court_centre_id, court_room_id);
