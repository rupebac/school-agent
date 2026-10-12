ALTER TABLE school_case ADD COLUMN information_only boolean NOT NULL DEFAULT false;
ALTER TABLE school_case ADD COLUMN info_override boolean;
ALTER TABLE school_case ADD COLUMN classification_review boolean NOT NULL DEFAULT false;
ALTER TABLE school_case ADD COLUMN completion_snapshot text;
UPDATE school_case c SET information_only = true
WHERE status = 'INFORMATION_ONLY'
  AND NOT EXISTS (SELECT 1 FROM parent_action a WHERE a.case_id=c.id AND a.required AND a.status NOT IN ('COMPLETED','CANCELLED'))
  AND NOT EXISTS (SELECT 1 FROM child_requirement r WHERE r.case_id=c.id AND r.required AND r.status NOT IN ('DONE','CANCELLED'));

CREATE TABLE case_read (
  case_id uuid NOT NULL REFERENCES school_case(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  revision varchar(64) NOT NULL,
  read_at timestamptz NOT NULL,
  PRIMARY KEY(case_id,user_id)
);
CREATE TABLE case_summary (
  case_id uuid NOT NULL REFERENCES school_case(id) ON DELETE CASCADE,
  language varchar(8) NOT NULL,
  revision varchar(64) NOT NULL,
  body text NOT NULL,
  created_at timestamptz NOT NULL,
  PRIMARY KEY(case_id,language,revision)
);
ALTER TABLE source ADD COLUMN forwarded_by text;
ALTER TABLE source ADD COLUMN original_sent_at timestamptz;
