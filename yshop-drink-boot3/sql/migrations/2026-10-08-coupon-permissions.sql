-- A missing/ambiguous/disabled query descriptor or ancestor is a deployment
-- error. Reject before INSERT; never manufacture menus or change role grants.
DROP PROCEDURE IF EXISTS quality_check_coupon_permissions;
DELIMITER $$
CREATE PROCEDURE quality_check_coupon_permissions()
BEGIN
  IF (SELECT COUNT(*) FROM system_menu WHERE permission='coupon::query' AND deleted=0)<>1
    OR (SELECT COUNT(*) FROM system_menu query_menu JOIN system_menu ancestor ON ancestor.id=query_menu.parent_id
      WHERE query_menu.permission='coupon::query' AND query_menu.deleted=0 AND query_menu.status=0 AND query_menu.type IN (2,3)
        AND ancestor.deleted=0 AND ancestor.status=0 AND ancestor.type IN (1,2))<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='COUPON_PERMISSION_ANCESTOR_REQUIRED';
  END IF;
END$$
DELIMITER ;
CALL quality_check_coupon_permissions();
DROP PROCEDURE quality_check_coupon_permissions;

-- Register missing permission descriptors only. Never grant or modify roles/user assignments.
-- Existing super_admin retains its existing all-permission semantics; ordinary roles require explicit review.
INSERT INTO system_menu(name,permission,type,sort,parent_id,path,icon,status)
SELECT '会员券查询','coupon:user:query',3,6,parent_id,'','',0 FROM system_menu
WHERE permission='coupon::query' AND deleted=0
AND NOT EXISTS(SELECT 1 FROM (SELECT permission,deleted FROM system_menu) existing WHERE existing.permission='coupon:user:query' AND existing.deleted=0)
LIMIT 1;
INSERT INTO system_menu(name,permission,type,sort,parent_id,path,icon,status)
SELECT '会员券导出','coupon:user:export',3,7,parent_id,'','',0 FROM system_menu
WHERE permission='coupon::query' AND deleted=0
AND NOT EXISTS(SELECT 1 FROM (SELECT permission,deleted FROM system_menu) existing WHERE existing.permission='coupon:user:export' AND existing.deleted=0)
LIMIT 1;
INSERT INTO system_menu(name,permission,type,sort,parent_id,path,icon,status)
SELECT '未预占会员券显式作废','coupon:user:delete',3,8,parent_id,'','',0 FROM system_menu
WHERE permission='coupon::query' AND deleted=0
AND NOT EXISTS(SELECT 1 FROM (SELECT permission,deleted FROM system_menu) existing WHERE existing.permission='coupon:user:delete' AND existing.deleted=0)
LIMIT 1;
