package co.yixiang.yshop.module.coupon.service.marketing;

import co.yixiang.yshop.framework.security.core.util.SecurityFrameworkUtils;
import co.yixiang.yshop.module.coupon.dal.dataobject.coupon.CouponDO;
import co.yixiang.yshop.module.store.service.storeshop.StoreAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Statement;
import java.time.*;
import java.util.*;
import static co.yixiang.yshop.module.coupon.service.marketing.CouponPolicy.*;

@Service
@RequiredArgsConstructor
public class CouponMarketingService {
    private final JdbcTemplate jdbc;
    private final StoreAccessService access;
    private Clock clock = Clock.system(ZoneId.of("Asia/Shanghai"));
    public LocalDateTime now() { return LocalDateTime.now(clock); }
    public static String digest(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    public static String codeHash(String code) {
        // Read compatibility only. New codes are never accepted from caller-provided strings.
        if (code == null || !code.matches("[A-Za-z0-9_-]{4,32}")) throw reject("COUPON_CODE_INVALID");
        return digest(code);
    }
    private static final java.security.SecureRandom CODE_RANDOM = new java.security.SecureRandom();
    public static String generateCode() {
        byte[] entropy = new byte[24]; // 192 uniformly random bits, not a length/character heuristic.
        CODE_RANDOM.nextBytes(entropy);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }
    public void requireScope(String scope) {
        var shops = shops(scope); var allowed = access.allowedShopIds();
        if (allowed != null && (shops.contains(0L) || !allowed.containsAll(shops)))
            throw new org.springframework.security.access.AccessDeniedException("COUPON_STORE_ACCESS_DENIED");
    }
    public Map<String,Object> requireTemplate(long id, boolean lock) {
        var rows=jdbc.queryForList("SELECT *, (type+0) AS coupon_type FROM yshop_coupon WHERE id=? AND deleted=0"+(lock?" FOR UPDATE":""),id);
        if(rows.size()!=1) throw reject("COUPON_NOT_EXISTS");
        var row=rows.get(0);row.put("type",row.get("coupon_type"));requireScope(Objects.toString(row.get("shop_id"),""));return row;
    }
    /** CRUD and export use the same full-resource scope, never stale token shop_id. */
    public String adminScopeRegex() {
        var allowed=access.allowedShopIds();
        if(allowed==null) return null;
        String ids=allowed.stream().sorted().map(Object::toString).collect(java.util.stream.Collectors.joining("|"));
        return "^("+ids+")(,("+ids+"))*$";
    }
    public String validate(CouponDO c, Map<String,Object> previous) {
        if (previous != null && number(previous,"template_version") > 0
                && !Objects.equals(c.getTemplateVersion(), number(previous,"template_version")))
            throw reject("COUPON_TEMPLATE_CHANGED_REFRESH");
        requireScope(c.getShopId());
        var scope=shops(c.getShopId());
        if(scope.contains(0L)) c.setShopName("全部门店");
        else {
            var names=new ArrayList<String>();
            for(long id:scope) {
                var rows=jdbc.queryForList("SELECT name FROM yshop_store_shop WHERE id=? AND deleted=0",String.class,id);
                if(rows.size()!=1) throw reject("COUPON_STORE_NOT_EXISTS");
                names.add(rows.get(0));
            }
            c.setShopName(String.join(",",names));
        }
        c.setShopId(scope.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(",")));
        if(c.getTitle()==null || c.getTitle().isBlank() || c.getTitle().length()>50
                || c.getIsSwitch()==null || c.getType()==null
                || !Set.of(0,1).contains(c.getIsSwitch()) || !Set.of(0,1,2).contains(c.getType())) throw reject("COUPON_TEMPLATE_INVALID");
        c.setLeast(amount(c.getLeast()));c.setValue(amount(c.getValue()));
        if(c.getValue().signum()<=0) throw reject("COUPON_INVALID_AMOUNT");
        if(c.getStartTime()==null || c.getEndTime()==null || !c.getEndTime().isAfter(c.getStartTime())) throw reject("COUPON_USE_WINDOW_INVALID");
        if(c.getClaimStartTime()==null) c.setClaimStartTime(c.getStartTime());
        if(c.getClaimEndTime()==null) c.setClaimEndTime(c.getEndTime());
        if(!c.getClaimEndTime().isAfter(c.getClaimStartTime()) || c.getClaimEndTime().isAfter(c.getEndTime())) throw reject("COUPON_CLAIM_WINDOW_INVALID");
        if(c.getDistribute()==null || c.getDistribute()<0 || c.getDistribute()>1000000 || c.getLimit()==null || c.getLimit()<1 || c.getLimit()>1000) throw reject("COUPON_LIMIT_INVALID");
        long actual=previous==null?0:jdbc.queryForObject("SELECT COUNT(*) FROM yshop_coupon_user WHERE coupon_id=?",Long.class,c.getId());
        long issued=previous==null?0:Math.max(actual,number(previous,"receive"));
        if(c.getDistribute()<issued || c.getLimit()<(previous==null?0:Objects.requireNonNull(jdbc.queryForObject("SELECT COALESCE(MAX(n),0) FROM (SELECT COUNT(*) n FROM yshop_coupon_user WHERE coupon_id=? GROUP BY user_id) counted",Long.class,c.getId()))))
            throw reject("COUPON_LIMIT_BELOW_ISSUED");
        c.setReceive(Math.toIntExact(issued));
        c.setCouponKind(Objects.requireNonNullElse(c.getCouponKind(),"REGULAR"));
        c.setClaimMode(Objects.requireNonNullElse(c.getClaimMode(),"PUBLIC"));
        if(!Set.of("REGULAR","NEW_USER").contains(c.getCouponKind()) || !Set.of("PUBLIC","CODE").contains(c.getClaimMode())) throw reject("COUPON_TEMPLATE_INVALID");
        if(previous!=null && issued>0 && !c.getCouponKind().equals(previous.get("coupon_kind"))) throw reject("COUPON_ISSUED_KIND_IMMUTABLE");
        if(c.getScore()!=null && c.getScore()!=0) throw reject("COUPON_POINTS_NOT_SUPPORTED");
        c.setScore(0); c.setWeigh(Objects.requireNonNullElse(c.getWeigh(),0));
        if(Math.abs((long)c.getWeigh())>1000000) throw reject("COUPON_TEMPLATE_INVALID");
        c.setInstructions(Objects.requireNonNullElse(c.getInstructions(),""));c.setImage(Objects.requireNonNullElse(c.getImage(),""));
        if(c.getInstructions().length()>1000 || c.getImage().length()>150) throw reject("COUPON_TEMPLATE_INVALID");
        if(c.getExchangeCode()!=null && !c.getExchangeCode().isBlank()) throw reject("COUPON_CODE_SERVER_GENERATED_ONLY");
        String generated = previous==null && "CODE".equals(c.getClaimMode()) ? generateCode() : null;
        c.setRedemptionCodeHash(generated!=null?codeHash(generated):previous==null?null:(String)previous.get("redemption_code_hash"));
        boolean legacyCode = previous!=null && previous.get("exchange_code")!=null && !previous.get("exchange_code").toString().isBlank();
        if("CODE".equals(c.getClaimMode()) && c.getRedemptionCodeHash()==null && !legacyCode) throw reject("COUPON_CREATE_NEW_CODE_CAMPAIGN");
        c.setExchangeCode(null); // never copy a code into issued rights or a returned template
        c.setTemplateVersion(previous==null?1:number(previous,"template_version")+1);
        return generated;
    }
    public void auditTemplate(long id,String kind,String reason) {
        new CouponLifecycle(jdbc).audit(UUID.randomUUID().toString(),id,null,null,SecurityFrameworkUtils.getLoginUserId(),"ADMIN",kind,reason);
    }
    public String claimReason(Map<String,Object> c,long uid) {
        try {
            if(number(c,"deleted")!=0 || number(c,"is_switch")!=1) return "COUPON_DISABLED";
            LocalDateTime begin=time(c.get("claim_start_time")==null?c.get("start_time"):c.get("claim_start_time"));
            LocalDateTime end=time(c.get("claim_end_time")==null?c.get("end_time"):c.get("claim_end_time"));
            if(now().isBefore(begin)) return "COUPON_CLAIM_NOT_STARTED";
            if(!end.isAfter(now())) return "COUPON_CLAIM_EXPIRED";
            shops(Objects.toString(c.get("shop_id"),"")); amount(c.get("least"));
            if(number(c,"type")<0 || number(c,"type")>2 || number(c,"receive")<0 || number(c,"distribute")<0
                    || !time(c.get("end_time")).isAfter(time(c.get("start_time"))) || !end.isAfter(begin)
                    || end.isAfter(time(c.get("end_time")))) return "COUPON_REVIEW_REQUIRED";
            if(amount(c.get("value")).signum()<=0 || number(c,"limit")<1) return "COUPON_REVIEW_REQUIRED";
            if(number(c,"score")!=0) return "COUPON_POINTS_NOT_SUPPORTED";
            long actual=jdbc.queryForObject("SELECT COUNT(*) FROM yshop_coupon_user WHERE coupon_id=?",Long.class,c.get("id"));
            if(number(c,"receive")!=actual) return "COUPON_ISSUANCE_NEEDS_REVIEW";
            if(actual>=number(c,"distribute")) return "COUPON_SOLD_OUT";
            long owned=jdbc.queryForObject("SELECT COUNT(*) FROM yshop_coupon_user WHERE coupon_id=? AND user_id=?",Long.class,c.get("id"),uid);
            if(owned>=number(c,"limit")) return "COUPON_USER_LIMIT";
            if("NEW_USER".equals(c.get("coupon_kind"))) {
                var users=jdbc.queryForList("SELECT create_time FROM yshop_user WHERE id=? AND deleted=0",uid);
                if(users.size()!=1 || users.get(0).get("create_time")==null) return "COUPON_NEW_USER_NOT_ELIGIBLE";
                LocalDateTime registered=time(users.get(0).get("create_time"));
                if(c.get("create_time")==null || registered.isBefore(time(c.get("create_time"))) || registered.isBefore(begin) || registered.isAfter(now()) || !registered.isBefore(end)
                        || jdbc.queryForObject("SELECT COUNT(*) FROM yshop_coupon_newcomer WHERE user_id=?",Long.class,uid)>0)
                    return "COUPON_NEW_USER_NOT_ELIGIBLE";
            }
            return "AVAILABLE";
        } catch(RuntimeException invalid) { return "COUPON_REVIEW_REQUIRED"; }
    }
    @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public long claim(long uid,Long id,String code,String requestKey) {
        if(uid<=0 || uid>Integer.MAX_VALUE || (id==null)==(code==null || code.isBlank())) throw reject("COUPON_CLAIM_INPUT_INVALID");
        String mode=id==null?"CODE":"PUBLIC", hash=digest(mode+":"+(id==null?codeHash(code):id));
        if(requestKey==null || !requestKey.matches("[A-Za-z0-9_-]{8,64}")) throw reject("COUPON_REQUEST_KEY_REQUIRED");
        var prior=jdbc.queryForList("SELECT coupon_id FROM yshop_coupon_claim WHERE user_id=? AND request_key=?",uid,requestKey);
        if(id==null) {
            if(!prior.isEmpty()) id=number(prior.get(0),"coupon_id");
            else {
                var matches=jdbc.queryForList("SELECT id FROM yshop_coupon WHERE deleted=0 AND (redemption_code_hash=? OR (redemption_code_hash IS NULL AND exchange_code=?))",Long.class,codeHash(code),code);
                if(matches.size()!=1) throw reject("COUPON_CODE_INVALID");id=matches.get(0);
            }
        }
        var rows=jdbc.queryForList("SELECT *, (type+0) AS coupon_type FROM yshop_coupon WHERE id=? FOR UPDATE",id);
        if(rows.size()!=1) throw reject("COUPON_NOT_EXISTS");var c=rows.get(0);c.put("type",c.get("coupon_type"));
        // Hidden code activities must not be discoverable by probing the public id-only path.
        if("CODE".equals(c.get("claim_mode")) && !"CODE".equals(mode)) throw reject("COUPON_NOT_EXISTS");
        var replay=jdbc.queryForList("SELECT * FROM yshop_coupon_claim WHERE user_id=? AND request_key=? FOR UPDATE",uid,requestKey);
        if(!replay.isEmpty()) {
            var saved=replay.get(0);
            if(number(saved,"coupon_id")!=id || !hash.equals(saved.get("request_hash"))) throw reject("COUPON_IDEMPOTENCY_CONFLICT");
            return number(saved,"coupon_user_id");
        }
        if(jdbc.queryForObject("SELECT COUNT(*) FROM yshop_user WHERE id=? AND deleted=0",Long.class,uid)!=1) throw reject("COUPON_MEMBER_NOT_FOUND");
        if("CODE".equals(mode) && !(codeHash(code).equals(c.get("redemption_code_hash")) || (c.get("redemption_code_hash")==null && code.equals(c.get("exchange_code"))))) throw reject("COUPON_CODE_INVALID");
        String reason=claimReason(c,uid);if(!"AVAILABLE".equals(reason)) throw reject(reason);
        var key=new GeneratedKeyHolder(); final long coupon=id;
        if(jdbc.update(conn->{var p=conn.prepareStatement("INSERT INTO yshop_coupon_user(shop_id,shop_name,title,least,`value`,start_time,end_time,type,score,instructions,image,user_id,status,coupon_id,template_version,create_time,update_time) VALUES(?,?,?,?,?,?,?,?,0,?,?,?,0,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",Statement.RETURN_GENERATED_KEYS);
            Object[] args={c.get("shop_id"),c.get("shop_name"),c.get("title"),c.get("least"),c.get("value"),c.get("start_time"),c.get("end_time"),c.get("type"),Objects.toString(c.get("instructions"),""),Objects.toString(c.get("image"),""),uid,coupon,c.get("template_version")};for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;},key)!=1 || key.getKey()==null) throw reject("COUPON_CLAIM_FAILED");
        long instance=key.getKey().longValue();
        if("NEW_USER".equals(c.get("coupon_kind"))) {
            try {
                if(jdbc.update("INSERT INTO yshop_coupon_newcomer(user_id,coupon_id,coupon_user_id,registered_at) SELECT ?,?,?,create_time FROM yshop_user WHERE id=? AND deleted=0",uid,id,instance,uid)!=1)throw reject("COUPON_NEW_USER_NOT_ELIGIBLE");
            } catch(org.springframework.dao.DuplicateKeyException conflict) {throw reject("COUPON_NEW_USER_NOT_ELIGIBLE");}
        }
        if(jdbc.update("UPDATE yshop_coupon SET receive=receive+1 WHERE id=? AND is_switch=1 AND receive<distribute AND receive=?",id,number(c,"receive"))!=1) throw reject("COUPON_SOLD_OUT");
        jdbc.update("INSERT INTO yshop_coupon_claim(user_id,request_key,coupon_id,coupon_user_id,claim_mode,request_hash) VALUES(?,?,?,?,?,?)",uid,requestKey,id,instance,mode,hash);
        new CouponLifecycle(jdbc).audit("CLAIM:"+instance,id,instance,null,uid,"MEMBER","CLAIM","NEW_USER".equals(c.get("coupon_kind"))?"服务端注册资格，一次新人权益":"免费领取，额度与权益同事务");
        return instance;
    }
    public String state(Map<String,Object> instance) {return new CouponLifecycle(jdbc).state(instance,now());}
    public Map<String,Long> statistics(long id) {
        requireTemplate(id,false);
        var rows=jdbc.queryForList("SELECT *, (type+0) AS coupon_type FROM yshop_coupon_user WHERE coupon_id=?",id);
        var counts=new LinkedHashMap<String,Long>();
        for(String key:List.of("AVAILABLE","RESERVED","USED","EXPIRED","NOT_YET_VALID","INVALID","REVIEW_REQUIRED"))counts.put(key,0L);
        var users=new HashSet<Long>();
        for(var row:rows){row.put("type",row.get("coupon_type"));String s=state(row);counts.merge(s,1L,Long::sum);users.add(number(row,"user_id"));}
        var template=requireTemplate(id,false);counts.put("CLAIMED",(long)rows.size());counts.put("CLAIMANTS",(long)users.size());counts.put("DISTRIBUTE",number(template,"distribute"));
        counts.put("REMAINING",Math.max(0,number(template,"distribute")-Math.max(number(template,"receive"),rows.size())));
        counts.put("COUNTER_MISMATCH",number(template,"receive")==rows.size()?0L:1L);return counts;
    }
    /** Privileged read-only historical investigation. Never repairs, reissues or clears markers. */
    @Transactional(readOnly=true)
    public Map<String,Long> legacyAudit() {
        access.requireHeadquarters();
        var counts=new LinkedHashMap<String,Long>();
        for(String k:List.of("INSTANCES","AVAILABLE","RESERVED","USED","EXPIRED","NOT_YET_VALID","INVALID","REVIEW_REQUIRED","MISSING_TEMPLATE","MISSING_ORDER","DISCOUNT_MISMATCH","COUNTER_MISMATCH"))counts.put(k,0L);
        var rows=jdbc.queryForList("SELECT *, (type+0) AS coupon_type FROM yshop_coupon_user");
        counts.put("INSTANCES",(long)rows.size());
        for(var row:rows){
            row.put("type",row.get("coupon_type"));counts.merge(state(row),1L,Long::sum);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM yshop_coupon WHERE id=? AND deleted=0",Long.class,row.get("coupon_id"))==0)counts.merge("MISSING_TEMPLATE",1L,Long::sum);
            if(row.get("reserved_order_id")!=null){
                var orders=jdbc.queryForList("SELECT coupon_id,coupon_price,total_price FROM yshop_store_order WHERE order_id=? AND uid=?",row.get("reserved_order_id"),row.get("user_id"));
                if(orders.size()!=1)counts.merge("MISSING_ORDER",1L,Long::sum);
                else try{var o=orders.get(0);if(number(o,"coupon_id")!=number(row,"id")||amount(o.get("coupon_price")).compareTo(amount(row.get("value")).min(amount(o.get("total_price"))))!=0)counts.merge("DISCOUNT_MISMATCH",1L,Long::sum);}catch(RuntimeException invalid){counts.merge("DISCOUNT_MISMATCH",1L,Long::sum);}
            }
        }
        for(var template:jdbc.queryForList("SELECT id,receive FROM yshop_coupon"))if(number(template,"receive")!=jdbc.queryForObject("SELECT COUNT(*) FROM yshop_coupon_user WHERE coupon_id=?",Long.class,template.get("id")))counts.merge("COUNTER_MISMATCH",1L,Long::sum);
        return counts;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public void invalidate(long instance,String reason) {
        if(reason==null || reason.isBlank() || reason.length()>200)throw reject("COUPON_REASON_REQUIRED");
        var rows=jdbc.queryForList("SELECT *, (type+0) AS coupon_type FROM yshop_coupon_user WHERE id=? FOR UPDATE",instance);
        if(rows.size()!=1)throw reject("COUPON_NOT_EXISTS");var row=rows.get(0);row.put("type",row.get("coupon_type"));requireScope(Objects.toString(row.get("shop_id"),""));
        // No order lock is acquired in this admin path. Only pristine, unreserved rights can change.
        if(number(row,"status")!=0 || row.get("reserved_order_id")!=null || row.get("redeemed_at")!=null || number(row,"deleted")!=0 || row.get("invalid_reason")!=null)throw reject("COUPON_NOT_INVALIDATABLE");
        jdbc.update("UPDATE yshop_coupon_user SET invalid_reason=?,status=1 WHERE id=? AND status=0 AND reserved_order_id IS NULL",reason,instance);
        new CouponLifecycle(jdbc).audit(UUID.randomUUID().toString(),number(row,"coupon_id"),instance,null,SecurityFrameworkUtils.getLoginUserId(),"ADMIN","INVALIDATE",reason);
    }
}
