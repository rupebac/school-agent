CREATE INDEX email_delivery_original_lookup ON email_delivery(child_id, original_message_id);
CREATE INDEX email_delivery_fingerprint_lookup ON email_delivery(child_id, fingerprint);
CREATE INDEX email_delivery_thread_lookup ON email_delivery(child_id, gmail_thread_id);
