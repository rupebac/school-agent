-- Extraction and vision should stay close to the source; reasoning a little
-- more conservative than the provider default. Only fill empty temperatures
-- so a family override in Settings is left alone.

UPDATE model_config SET temperature = 0.10, updated_at = now()
 WHERE role IN ('EXTRACTION', 'VISION') AND temperature IS NULL;

UPDATE model_config SET temperature = 0.20, updated_at = now()
 WHERE role = 'REASONING' AND temperature IS NULL;
