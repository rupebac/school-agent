-- Scope before collecting text. English stemming handles copying/copy and fee/fees;
-- simple token matching preserves German names, numbers and other languages.
WITH visible AS MATERIALIZED (
    SELECT id, title, summary, status, importance_score, updated_at
    FROM school_case
    WHERE (child_id IS NULL OR child_id IN (:children))
      AND (CAST(:kid AS uuid) IS NULL OR child_id = CAST(:kid AS uuid))
), query AS (
    SELECT to_tsquery('english', :terms) AS en, to_tsquery('simple', :terms) AS raw
), fragments AS MATERIALIZED (
    SELECT id AS case_id, title AS body, 'A'::"char" AS weight FROM visible
    UNION ALL SELECT id, summary, 'B' FROM visible WHERE summary IS NOT NULL
    UNION ALL SELECT a.case_id, concat_ws(' ', a.title, a.description), 'B'
        FROM parent_action a JOIN visible v ON v.id = a.case_id WHERE a.status <> 'CANCELLED'
    UNION ALL SELECT r.case_id, r.description, 'B'
        FROM child_requirement r JOIN visible v ON v.id = r.case_id WHERE r.status <> 'CANCELLED'
    UNION ALL SELECT e.case_id, concat_ws(' ', e.title, e.location), 'B'
        FROM school_event e JOIN visible v ON v.id = e.case_id WHERE e.status <> 'CANCELLED'
    UNION ALL SELECT s.case_id, concat_ws(' ', s.title, s.sender), 'C'
        FROM source s JOIN visible v ON v.id = s.case_id
    UNION ALL SELECT e.case_id, e.subject, 'C'
        FROM evidence e JOIN visible v ON v.id = e.case_id
    UNION ALL SELECT s.case_id, t.translated_text, 'D'
        FROM source_translation t JOIN source s ON s.id = t.source_id AND s.content_hash = t.content_hash
        JOIN visible v ON v.id = s.case_id
), documents AS (
    SELECT v.*,
        setweight(to_tsvector('english', v.title), 'A') ||
        setweight(to_tsvector('english', coalesce(v.summary, '')), 'B') ||
        setweight(to_tsvector('english', string_agg(f.body, ' ')), 'C') AS en,
        to_tsvector('simple', string_agg(f.body, ' ')) AS raw
    FROM visible v JOIN fragments f ON f.case_id = v.id
    GROUP BY v.id, v.title, v.summary, v.status, v.importance_score, v.updated_at
), matched AS (
    SELECT d.*, greatest(ts_rank_cd(d.en, q.en), ts_rank_cd(d.raw, q.raw)) AS relevance
    FROM documents d CROSS JOIN query q
    WHERE d.en @@ q.en OR d.raw @@ q.raw
    ORDER BY relevance DESC, updated_at DESC, id
    LIMIT 50
)
SELECT m.id, m.title, m.status, m.importance_score,
    coalesce((SELECT f.body FROM fragments f CROSS JOIN query q
              WHERE f.case_id = m.id AND (to_tsvector('english', f.body) @@ q.en OR to_tsvector('simple', f.body) @@ q.raw)
              ORDER BY f.weight, length(f.body) LIMIT 1), m.summary, m.title) AS snippet
FROM matched m ORDER BY m.relevance DESC, m.updated_at DESC, m.id
