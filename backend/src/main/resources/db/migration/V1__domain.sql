-- V1: full domain model, ARCHITECTURE.md §6.
-- Conventions: UUID PKs assigned by the app; enums as varchar(32);
-- instants as timestamptz; distilled payloads as jsonb.

-- ---------- identity ----------

CREATE TABLE app_user (
    id              uuid PRIMARY KEY,
    name            text NOT NULL UNIQUE,
    display_name    text NOT NULL,
    password_hash   text NOT NULL,
    whatsapp_number text,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL
);

CREATE TABLE child (
    id                 uuid PRIMARY KEY,
    name               text NOT NULL,
    school             text,
    class_name         text,
    gmail_address      text,
    google_calendar_id text,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL
);

-- ---------- case core ----------

CREATE TABLE school_case (
    id                    uuid PRIMARY KEY,
    child_id              uuid REFERENCES child (id),
    title                 text NOT NULL,
    summary               text,
    category              varchar(32) NOT NULL,
    status                varchar(32) NOT NULL,
    importance            varchar(32) NOT NULL,
    confidence            numeric(4,3),
    investigation_complete boolean NOT NULL DEFAULT false,
    investigation_notes   text,
    dedup_key             text UNIQUE,
    opened_at             timestamptz NOT NULL,
    closed_at             timestamptz,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL
);
CREATE INDEX idx_school_case_status ON school_case (status);

CREATE TABLE case_status_entry (
    id          uuid PRIMARY KEY,
    case_id     uuid NOT NULL REFERENCES school_case (id) ON DELETE CASCADE,
    from_status varchar(32),
    to_status   varchar(32) NOT NULL,
    reason      text,
    actor       varchar(32) NOT NULL,
    created_at  timestamptz NOT NULL
);
CREATE INDEX idx_case_status_entry_case ON case_status_entry (case_id);

CREATE TABLE case_change (
    id          uuid PRIMARY KEY,
    case_id     uuid NOT NULL REFERENCES school_case (id) ON DELETE CASCADE,
    change_type varchar(32) NOT NULL,
    target_type varchar(32) NOT NULL,
    target_id   uuid,
    before_json jsonb,
    after_json  jsonb,
    evidence_id uuid,
    created_at  timestamptz NOT NULL
);
CREATE INDEX idx_case_change_case ON case_change (case_id);

-- ---------- acquisition ----------

CREATE TABLE source (
    id               uuid PRIMARY KEY,
    case_id          uuid REFERENCES school_case (id),
    source_type      varchar(32) NOT NULL,
    external_id      text NOT NULL,
    dedup_key        text NOT NULL UNIQUE,
    content_hash     text NOT NULL,
    title            text,
    sender           text,
    received_at      timestamptz,
    canonical_url    text,
    storage_ref      text,
    mime_type        text,
    byte_size        bigint,
    fetch_status     varchar(32) NOT NULL,
    failure_category varchar(32),
    failure_message  text,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT uq_source_type_external UNIQUE (source_type, external_id)
);
CREATE INDEX idx_source_case ON source (case_id);

CREATE TABLE evidence (
    id           uuid PRIMARY KEY,
    case_id      uuid NOT NULL REFERENCES school_case (id) ON DELETE CASCADE,
    source_id    uuid NOT NULL REFERENCES source (id) ON DELETE CASCADE,
    kind         varchar(32) NOT NULL,
    subject      text NOT NULL,
    payload      jsonb,
    confidence   numeric(4,3),
    extracted_by text,
    dedup_key    text NOT NULL UNIQUE,
    created_at   timestamptz NOT NULL
);
CREATE INDEX idx_evidence_case ON evidence (case_id);

CREATE TABLE investigation (
    id            uuid PRIMARY KEY,
    case_id       uuid NOT NULL REFERENCES school_case (id) ON DELETE CASCADE,
    status        varchar(32) NOT NULL,
    started_at    timestamptz NOT NULL,
    finished_at   timestamptz,
    pages_visited integer NOT NULL DEFAULT 0,
    ai_calls      integer NOT NULL DEFAULT 0,
    tokens_used   integer NOT NULL DEFAULT 0,
    limits_hit    jsonb,
    notes         text
);
CREATE INDEX idx_investigation_case ON investigation (case_id);

CREATE TABLE investigation_step (
    id              uuid PRIMARY KEY,
    investigation_id uuid NOT NULL REFERENCES investigation (id) ON DELETE CASCADE,
    step_type       varchar(32) NOT NULL,
    target          text NOT NULL,
    outcome         varchar(32) NOT NULL,
    detail          jsonb,
    created_at      timestamptz NOT NULL
);
CREATE INDEX idx_investigation_step_inv ON investigation_step (investigation_id);

-- ---------- obligations ----------

CREATE TABLE parent_action (
    id                uuid PRIMARY KEY,
    case_id           uuid NOT NULL REFERENCES school_case (id) ON DELETE CASCADE,
    title             text NOT NULL,
    description       text,
    due_at            timestamptz,
    assigned_to       uuid REFERENCES app_user (id),
    status            varchar(32) NOT NULL,
    external_url      text,
    recommended_input jsonb,
    confidence        numeric(4,3),
    dedup_key         text NOT NULL UNIQUE,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    completed_at      timestamptz
);
CREATE INDEX idx_parent_action_case ON parent_action (case_id);
CREATE INDEX idx_parent_action_status ON parent_action (status);

CREATE TABLE child_requirement (
    id          uuid PRIMARY KEY,
    case_id     uuid NOT NULL REFERENCES school_case (id) ON DELETE CASCADE,
    description text NOT NULL,
    relevant_on date,
    status      varchar(32) NOT NULL,
    dedup_key   text NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL
);
CREATE INDEX idx_child_requirement_case ON child_requirement (case_id);

-- ---------- time ----------

CREATE TABLE school_event (
    id                uuid PRIMARY KEY,
    case_id           uuid NOT NULL REFERENCES school_case (id) ON DELETE CASCADE,
    title             text NOT NULL,
    event_type        varchar(32) NOT NULL,
    start_time        timestamptz NOT NULL,
    end_time          timestamptz,
    all_day           boolean NOT NULL DEFAULT false,
    location          text,
    status            varchar(32) NOT NULL,
    google_event_id   text UNIQUE,
    google_sync_status varchar(32) NOT NULL,
    dedup_key         text NOT NULL UNIQUE,
    superseded_by_id  uuid,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL
);
CREATE INDEX idx_school_event_case ON school_event (case_id);
CREATE INDEX idx_school_event_start ON school_event (start_time);

-- ---------- projection & ops ----------

CREATE TABLE notification (
    id           uuid PRIMARY KEY,
    case_id      uuid REFERENCES school_case (id) ON DELETE CASCADE,
    level        varchar(32) NOT NULL,
    channel      varchar(32) NOT NULL,
    recipient_id uuid REFERENCES app_user (id),
    message      text NOT NULL,
    status       varchar(32) NOT NULL,
    sent_at      timestamptz,
    dedup_key    text NOT NULL UNIQUE,
    created_at   timestamptz NOT NULL
);
CREATE INDEX idx_notification_case ON notification (case_id);

CREATE TABLE audit_entry (
    id         uuid PRIMARY KEY,
    case_id    uuid REFERENCES school_case (id) ON DELETE CASCADE,
    actor      varchar(32) NOT NULL,
    action     text NOT NULL,
    detail     jsonb,
    created_at timestamptz NOT NULL
);
CREATE INDEX idx_audit_entry_case ON audit_entry (case_id);
CREATE INDEX idx_audit_entry_created ON audit_entry (created_at);

CREATE TABLE connector_health (
    connector           varchar(32) PRIMARY KEY,
    status              varchar(32) NOT NULL,
    last_success_at     timestamptz,
    last_failure_at     timestamptz,
    last_error          text,
    consecutive_failures integer NOT NULL DEFAULT 0,
    updated_at          timestamptz NOT NULL
);

CREATE TABLE model_config (
    id               uuid PRIMARY KEY,
    role             varchar(32) NOT NULL UNIQUE,
    provider         text NOT NULL,
    model_name       text NOT NULL,
    reasoning_effort text,
    temperature      numeric(3,2),
    enabled          boolean NOT NULL DEFAULT true,
    updated_at       timestamptz NOT NULL
);

CREATE TABLE ai_usage (
    id             uuid PRIMARY KEY,
    case_id        uuid REFERENCES school_case (id) ON DELETE CASCADE,
    role           varchar(32) NOT NULL,
    model          text NOT NULL,
    purpose        text,
    input_tokens   integer NOT NULL DEFAULT 0,
    output_tokens  integer NOT NULL DEFAULT 0,
    calls          integer NOT NULL DEFAULT 1,
    estimated_cost numeric(12,6),
    created_at     timestamptz NOT NULL
);
CREATE INDEX idx_ai_usage_case ON ai_usage (case_id);

CREATE TABLE family_rule (
    id         uuid PRIMARY KEY,
    rule_key   text NOT NULL UNIQUE,
    rule_value jsonb,
    enabled    boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE setting (
    key        text PRIMARY KEY,
    value_json jsonb,
    updated_at timestamptz NOT NULL
);

CREATE TABLE secret_ref (
    key         text PRIMARY KEY,
    cipher_text text NOT NULL,
    updated_at  timestamptz NOT NULL
);
