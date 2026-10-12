-- Two models only: TEXT (letters, facts, summaries, translation) and VISION
-- (scanned pages / images). glm-5.3 is text-only; glm-5.3-flash is multimodal.

UPDATE model_config
   SET role = 'TEXT',
       model_name = 'glm-5.3',
       temperature = 0.20,
       enabled = true,
       updated_at = now()
 WHERE role = 'REASONING';

UPDATE model_config
   SET model_name = 'glm-5.3-flash',
       temperature = 0.10,
       enabled = true,
       updated_at = now()
 WHERE role = 'VISION';

DELETE FROM model_config WHERE role IN ('EXTRACTION', 'FALLBACK');
