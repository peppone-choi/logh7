ALTER TABLE original_mail_message
    ADD COLUMN is_read boolean NOT NULL DEFAULT false,
    ADD COLUMN read_at timestamptz,
    ADD CONSTRAINT original_mail_message_read_state_check
        CHECK ((is_read AND read_at IS NOT NULL) OR (NOT is_read AND read_at IS NULL));

CREATE INDEX original_mail_message_account_unread_idx
    ON original_mail_message(account_id, recipient_character_id, mail_id)
    WHERE NOT is_read;
