-- V6: family choice — glm-5.3-flash for both extraction and reasoning
-- (cheap, fast, subscription-covered via the anthropic-compatible endpoint).

UPDATE model_config SET model_name = 'glm-5.3-flash', updated_at = now() WHERE role = 'EXTRACTION';
UPDATE model_config SET model_name = 'glm-5.3-flash', updated_at = now() WHERE role = 'REASONING';
