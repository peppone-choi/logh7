ALTER TABLE original_mail_message
    ADD COLUMN sender_deleted boolean NOT NULL DEFAULT false,
    ADD COLUMN recipient_deleted boolean NOT NULL DEFAULT false;

CREATE INDEX original_mail_message_account_sender_visible_idx
    ON original_mail_message(account_id, sender_character_id, mail_id)
    WHERE NOT sender_deleted;

CREATE INDEX original_mail_message_account_recipient_visible_idx
    ON original_mail_message(account_id, recipient_character_id, mail_id)
    WHERE NOT recipient_deleted;
