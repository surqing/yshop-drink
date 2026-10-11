-- Apply ONLY with all member identity writers stopped and a verified snapshot.
-- Full-value, case-sensitive uniqueness includes deleted rows; NULL means no identity.
-- No user deletion, merge, asset movement or member-id change is permitted.
DROP PROCEDURE IF EXISTS quality_migrate_member_identity;
DELIMITER $$
CREATE PROCEDURE quality_migrate_member_identity()
BEGIN
  DECLARE conflicts BIGINT DEFAULT 0;
  SELECT COUNT(*) INTO conflicts FROM yshop_user WHERE
    (COALESCE(LENGTH(mobile),0)>0 AND NOT REGEXP_LIKE(mobile,'^(\\+86|0086)?1(3[0-9]|4[014-9]|5[0-35-9]|6[25-7]|7[0-8]|8[0-9]|9[0-35-9])[0-9]{8}$','c')) OR
    (COALESCE(LENGTH(openid),0)>0 AND NOT REGEXP_LIKE(openid,'^[A-Za-z0-9_-]{1,100}$','c')) OR
    (COALESCE(LENGTH(routine_openid),0)>0 AND NOT REGEXP_LIKE(routine_openid,'^[A-Za-z0-9_-]{1,100}$','c')) OR COALESCE(tenant_id,0)<>0;
  IF conflicts<>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MEMBER_IDENTITY_INVALID_OR_TENANT_CONFLICT'; END IF;
  SELECT COUNT(*) INTO conflicts FROM (
    SELECT RIGHT(mobile,11) value FROM yshop_user WHERE COALESCE(LENGTH(mobile),0)>0 GROUP BY value HAVING COUNT(*)>1
    UNION ALL SELECT BINARY openid FROM yshop_user WHERE COALESCE(LENGTH(openid),0)>0 GROUP BY BINARY openid HAVING COUNT(*)>1
    UNION ALL SELECT BINARY routine_openid FROM yshop_user WHERE COALESCE(LENGTH(routine_openid),0)>0 GROUP BY BINARY routine_openid HAVING COUNT(*)>1
  ) duplicates;
  IF conflicts<>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MEMBER_IDENTITY_NORMALIZED_DUPLICATES'; END IF;
  SELECT COUNT(*) INTO conflicts FROM system_social_user_bind WHERE user_type=1;
  IF conflicts<>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MEMBER_GENERIC_SOCIAL_REQUIRES_REVIEW'; END IF;
  SELECT COUNT(*) INTO conflicts FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_user' AND INDEX_NAME IN ('uk_member_mobile','uk_member_openid','uk_member_routine_openid');
  IF conflicts NOT IN (0,3) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MEMBER_IDENTITY_PARTIAL_INDEX_MIGRATION'; END IF;
  IF conflicts=3 THEN
    IF EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_user'
      AND INDEX_NAME IN ('uk_member_mobile','uk_member_openid','uk_member_routine_openid')
      AND (NON_UNIQUE<>0 OR SEQ_IN_INDEX<>1 OR SUB_PART IS NOT NULL OR COLUMN_NAME<>CASE INDEX_NAME WHEN 'uk_member_mobile' THEN 'mobile' WHEN 'uk_member_openid' THEN 'openid' ELSE 'routine_openid' END))
      OR EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yshop_user' AND COLUMN_NAME IN ('mobile','openid','routine_openid')
      AND (COLLATION_NAME<>'ascii_bin' OR IS_NULLABLE<>'YES' OR CHARACTER_MAXIMUM_LENGTH<>IF(COLUMN_NAME='mobile',11,100))) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MEMBER_IDENTITY_INDEX_SHAPE_MISMATCH';
    END IF;
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='system_social_client') THEN
    IF EXISTS(SELECT 1 FROM system_social_client WHERE user_type=1 AND status=0 AND deleted=0
      AND (COALESCE(tenant_id,0)<>0 OR social_type NOT IN (31,34) OR
        (social_type=34 AND (COALESCE(@member_mini_appid,'')='' OR BINARY client_id<>BINARY @member_mini_appid)) OR
        (social_type=31 AND (COALESCE(@member_mp_appid,'')='' OR BINARY client_id<>BINARY @member_mp_appid)))) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MEMBER_APPID_CONFIGURATION_REQUIRES_VERIFICATION';
    END IF;
  END IF;
  -- DDL commits implicitly. All checks precede this first data mutation. Freeze writers
  -- until completion; restore the snapshot if ALTER fails after the canonical UPDATE.
  START TRANSACTION;
  UPDATE yshop_user SET mobile=IF(COALESCE(LENGTH(mobile),0)=0,NULL,RIGHT(mobile,11)),openid=IF(LENGTH(openid)=0,NULL,openid),routine_openid=IF(LENGTH(routine_openid)=0,NULL,routine_openid);
  COMMIT;
  IF conflicts=0 THEN
    ALTER TABLE yshop_user
      MODIFY mobile CHAR(11) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
      MODIFY openid VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
      MODIFY routine_openid VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
      ADD UNIQUE KEY uk_member_mobile(mobile),
      ADD UNIQUE KEY uk_member_openid(openid),
      ADD UNIQUE KEY uk_member_routine_openid(routine_openid);
  END IF;
END$$
DELIMITER ;
CALL quality_migrate_member_identity();
DROP PROCEDURE quality_migrate_member_identity;
