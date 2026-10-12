-- V3: ExternalForm domain object (spec §12) and evidence provenance on actions.

CREATE TABLE external_form (
    id                  uuid PRIMARY KEY,
    case_id             uuid NOT NULL REFERENCES school_case (id) ON DELETE CASCADE,
    url                 text NOT NULL,
    deadline            timestamptz,
    requires_consent    boolean NOT NULL DEFAULT false,
    requires_payment    boolean NOT NULL DEFAULT false,
    field_descriptions  jsonb,
    access_status       varchar(32) NOT NULL,
    last_inspected_at   timestamptz,
    notes               text,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL
);
CREATE INDEX idx_external_form_case ON external_form (case_id);

-- Which piece of evidence produced each derived action, spec §29 provenance.
ALTER TABLE parent_action ADD COLUMN evidence_id uuid REFERENCES evidence (id);
