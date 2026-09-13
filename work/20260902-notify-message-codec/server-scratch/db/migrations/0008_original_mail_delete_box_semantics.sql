UPDATE original_mail_message AS mail
SET sender_deleted = true,
    recipient_deleted = false
WHERE recipient_deleted
  AND NOT sender_deleted
  AND EXISTS (
      SELECT 1
      FROM domain_event AS event
      WHERE event.account_id = mail.account_id
        AND event.event_type = 'OriginalMailDeleted'
        AND (event.payload ->> 'mailId')::bigint = mail.mail_id
        AND (event.payload ->> 'box')::integer = 0)
  AND NOT EXISTS (
      SELECT 1
      FROM domain_event AS event
      WHERE event.account_id = mail.account_id
        AND event.event_type = 'OriginalMailDeleted'
        AND (event.payload ->> 'mailId')::bigint = mail.mail_id
        AND (event.payload ->> 'box')::integer = 1);
