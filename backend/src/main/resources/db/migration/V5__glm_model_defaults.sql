-- V5: GLM (Z.ai, OpenAI-compatible) model defaults per role (spec §13/§14).
-- Discovered live from the family's subscription via GET /models:
-- glm-5.3-flash (cheap/fast) for extraction, glm-5.3 (strong) for reasoning
-- and vision, glm-5-turbo as fallback. Editable later via the Settings UI.

UPDATE model_config SET model_name = 'glm-5.3-flash', enabled = true, updated_at = now() WHERE role = 'EXTRACTION';
UPDATE model_config SET model_name = 'glm-5.3',       enabled = true, updated_at = now() WHERE role = 'REASONING';
UPDATE model_config SET model_name = 'glm-5.3',       enabled = true, updated_at = now() WHERE role = 'VISION';
UPDATE model_config SET model_name = 'glm-5-turbo',   enabled = true, updated_at = now() WHERE role = 'FALLBACK';
