-- Run explicitly before enabling encrypted writes or the one-time application migration.
-- Repeatable column expansion only; does not read/delete/transform credential values.
ALTER TABLE merchant_details
    MODIFY COLUMN key_private MEDIUMTEXT NULL COMMENT 'Authenticated encrypted private key or API key',
    MODIFY COLUMN key_cert MEDIUMTEXT NULL COMMENT 'Authenticated encrypted client certificate material',
    MODIFY COLUMN key_cert_pwd MEDIUMTEXT NULL COMMENT 'Authenticated encrypted certificate password';
