-- Additive request identities only. No historical order/account/ledger rewrite.
-- Preflight completes before any persistent table mutation; partial schemas fail closed.
DROP PROCEDURE IF EXISTS quality_check_score_submission;
DELIMITER $$
CREATE PROCEDURE quality_check_score_submission()
BEGIN
  DECLARE matched INT DEFAULT 0;
  IF (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
      AND TABLE_NAME IN ('yshop_score_order','yshop_score_product','yshop_user','yshop_user_address','yshop_user_bill') AND ENGINE='InnoDB')<>5 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SCORE_SUBMISSION_LEGACY_SCHEMA_REQUIRED';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_score_submission') THEN
    IF (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_score_submission' AND ENGINE='InnoDB')<>1 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SCORE_SUBMISSION_SCHEMA_MISMATCH';
    END IF;
    SELECT COUNT(*) INTO matched FROM (
      SELECT 'uid' name,'bigint' kind,'NO' nullable,NULL def,NULL coll
      UNION ALL SELECT 'idempotency_key','varchar(64)','NO',NULL,'ascii_bin'
      UNION ALL SELECT 'request_hash','char(64)','NO',NULL,'ascii_bin'
      UNION ALL SELECT 'order_id','varchar(32)','NO',NULL,'ascii_bin'
      UNION ALL SELECT 'create_time','datetime','NO','CURRENT_TIMESTAMP',NULL
    ) expected JOIN information_schema.COLUMNS actual ON actual.TABLE_SCHEMA=DATABASE()
      AND actual.TABLE_NAME='yshop_score_submission' AND actual.COLUMN_NAME=expected.name
      AND actual.COLUMN_TYPE=expected.kind AND actual.IS_NULLABLE=expected.nullable
      AND (CASE WHEN expected.def='CURRENT_TIMESTAMP' THEN UPPER(REPLACE(actual.COLUMN_DEFAULT,'()',''))='CURRENT_TIMESTAMP'
           ELSE BINARY actual.COLUMN_DEFAULT <=> BINARY expected.def END)
      AND (expected.coll IS NULL OR actual.COLLATION_NAME=expected.coll)
      AND actual.EXTRA IN ('','DEFAULT_GENERATED');
    IF matched<>5 OR (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_score_submission')<>5 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SCORE_SUBMISSION_SCHEMA_MISMATCH';
    END IF;
    SELECT COUNT(*) INTO matched FROM (
      SELECT 'PRIMARY' name,1 seq,'uid' col
      UNION ALL SELECT 'PRIMARY',2,'idempotency_key'
      UNION ALL SELECT 'uk_score_submission_order',1,'order_id'
    ) expected JOIN information_schema.STATISTICS actual ON actual.TABLE_SCHEMA=DATABASE()
      AND actual.TABLE_NAME='yshop_score_submission' AND actual.INDEX_NAME=expected.name
      AND actual.SEQ_IN_INDEX=expected.seq AND actual.COLUMN_NAME=expected.col
      AND actual.NON_UNIQUE=0 AND actual.SUB_PART IS NULL AND actual.INDEX_TYPE='BTREE'
      AND actual.IS_VISIBLE='YES';
    IF matched<>3 OR (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_score_submission')<>3
      OR EXISTS(SELECT 1 FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE='yshop_score_submission')
      OR EXISTS(SELECT 1 FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='yshop_score_submission' AND CONSTRAINT_TYPE NOT IN ('PRIMARY KEY','UNIQUE')) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SCORE_SUBMISSION_SCHEMA_MISMATCH';
    END IF;
  END IF;
END$$
DELIMITER ;
CALL quality_check_score_submission();
DROP PROCEDURE quality_check_score_submission;
CREATE TABLE IF NOT EXISTS yshop_score_submission (
  uid BIGINT NOT NULL,
  idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  order_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(uid,idempotency_key),
  UNIQUE KEY uk_score_submission_order(order_id)
) ENGINE=InnoDB;
