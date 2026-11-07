-- Apply after importing the default families. Authored by Peppone Choi.
-- Keep explicit country overrides and any operator-edited currency.
DO $$
DECLARE
    added BOOLEAN;
BEGIN
    PERFORM revision FROM nation_definition_revision WHERE singleton = TRUE FOR UPDATE;

    INSERT INTO nation_currency (currency_key, display_name, symbol, minor_digits)
    VALUES ('imperial_mark', '제국마르크', '', 0)
    ON CONFLICT (currency_key) DO NOTHING;
    added := FOUND;

    UPDATE nation_family
    SET currency_key = 'imperial_mark'
    WHERE family_key IN ('empire', 'new_empire')
      AND currency_key = 'native_funds'
      AND EXISTS (
          SELECT 1 FROM nation_currency
          WHERE currency_key = 'native_funds' AND display_name = '원본 자금'
            AND symbol = '' AND minor_digits = 0
      );

    IF added OR FOUND THEN
        UPDATE nation_definition_revision SET revision = revision + 1 WHERE singleton = TRUE;
    END IF;
END $$;
