package co.yixiang.yshop.module.product.service.catalog;

import co.yixiang.yshop.framework.common.exception.ErrorCode;
import co.yixiang.yshop.framework.common.exception.util.ServiceExceptionUtil;
import co.yixiang.yshop.framework.common.util.json.JsonUtils;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

/** Non-stock customizations. Inventory remains exclusively in existing SKU rows. */
public final class CatalogOptions {
    private CatalogOptions() {}
    public record Condition(String groupId, String optionId) {}
    public static final class IntegerCount extends JsonDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser p,DeserializationContext c) throws IOException {
            if(p.currentToken()!=JsonToken.VALUE_NUMBER_INT) throw c.wrongTokenException(p,Integer.class,JsonToken.VALUE_NUMBER_INT,"CATALOG_INTEGER_REQUIRED");
            return p.getIntValue();
        }
    }
    public record Option(String id, String name, BigDecimal surcharge, boolean enabled, @JsonDeserialize(using=IntegerCount.class) int defaultQuantity) {}
    public record Group(String id, String name, String kind, boolean multiple, int min, int max,
                        int maxPerOption, boolean enabled, Condition when, List<Option> options) {}
    public record Configuration(List<Group> groups) {}
    public record Selection(String groupId, String optionId, @JsonDeserialize(using=IntegerCount.class) int quantity) {}
    public record Choice(long version, List<Selection> selections) {}
    public record Selected(String groupId, String groupName, String kind, String optionId,
                           String name, int quantity, BigDecimal surcharge) {}
    public record Quote(BigDecimal extra, List<Selected> selected) {}

    public static RuntimeException reject(String code) {
        return ServiceExceptionUtil.exception(new ErrorCode(1006004090, code));
    }
    public static BigDecimal money(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal("999999.99")) > 0)
            throw reject("CATALOG_PRICE_INVALID");
        try { return value.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException ex) { throw reject("CATALOG_PRICE_PRECISION"); }
    }
    private static void label(String s) {
        if (s == null || s.isBlank() || s.length() > 60) throw reject("CATALOG_LABEL_REQUIRED");
    }
    private static void id(String s) {
        if (s == null || !s.matches("[A-Za-z0-9_-]{1,40}")) throw reject("CATALOG_OPTION_ID_INVALID");
    }
    public static Configuration validate(Configuration c) {
        if (c == null || c.groups() == null || c.groups().size() > 20) throw reject("CATALOG_GROUPS_INVALID");
        Map<String, Set<String>> prior = new HashMap<>();
        for (var g : c.groups()) {
            if (g == null) throw reject("CATALOG_GROUP_INVALID");
            id(g.id()); label(g.name());
            if (prior.containsKey(g.id()) || !Set.of("CUSTOM", "TOPPING").contains(Objects.toString(g.kind(), ""))
                || g.min() < 0 || g.max() < Math.max(1,g.min()) || g.max() > 20
                || g.maxPerOption() < 1 || g.maxPerOption() > 10
                || (!g.multiple() && (g.max() != 1 || g.maxPerOption() != 1))
                || g.options() == null || g.options().isEmpty() || g.options().size() > 30)
                throw reject("CATALOG_GROUP_INVALID");
            if (g.when() != null && !prior.getOrDefault(g.when().groupId(), Set.of()).contains(g.when().optionId()))
                throw reject("CATALOG_CONDITION_INVALID");
            Set<String> ids = new HashSet<>(); int defaults = 0, capacity = 0;
            for (var o : g.options()) {
                if (o == null) throw reject("CATALOG_OPTION_INVALID");
                id(o.id()); label(o.name()); money(o.surcharge());
                if (!ids.add(o.id()) || o.defaultQuantity() < 0 || o.defaultQuantity() > g.maxPerOption()
                    || (!o.enabled() && o.defaultQuantity() > 0)) throw reject("CATALOG_OPTION_INVALID");
                defaults += o.defaultQuantity();
                if(o.enabled()) capacity += g.multiple() ? g.maxPerOption() : 1;
            }
            if (defaults > g.max() || (g.enabled() && capacity < g.min())) throw reject("CATALOG_DEFAULT_INVALID");
            prior.put(g.id(), ids);
        }
        if (JsonUtils.toJsonString(c).length() > 64000) throw reject("CATALOG_CONFIG_TOO_LARGE");
        return c;
    }
    public static Configuration read(Object json) {
        if (json == null || json.toString().isBlank()) return new Configuration(List.of());
        return validate(JsonUtils.parseObject(json.toString(), Configuration.class));
    }
    /** Caller holds product lock; never accepts a client price. */
    public static Quote quote(Map<String, Object> product, Choice choice) {
        long version = ((Number) product.getOrDefault("catalog_version", 0L)).longValue();
        List<Selection> selections = choice == null ? List.of() : choice.selections();
        if (selections == null || selections.size() > 100 || (version > 0 && (choice == null || choice.version() != version)))
            throw reject("CATALOG_CHANGED_REFRESH_REQUIRED");
        var c = read(product.get("catalog_config"));
        Map<String, Map<String,Integer>> chosen = new HashMap<>();
        for (var s : selections) {
            if (s == null || s.quantity() < 1 || s.quantity() > 10) throw reject("CATALOG_SELECTION_INVALID");
            if (chosen.computeIfAbsent(s.groupId(), k -> new HashMap<>()).putIfAbsent(s.optionId(), s.quantity()) != null)
                throw reject("CATALOG_DUPLICATE_OPTION");
        }
        BigDecimal extra = BigDecimal.ZERO.setScale(2); List<Selected> snapshot = new ArrayList<>();
        for (var g : c.groups()) {
            var picked = chosen.getOrDefault(g.id(), Map.of());
            boolean applies = g.enabled() && (g.when() == null || chosen.getOrDefault(g.when().groupId(), Map.of()).containsKey(g.when().optionId()));
            if (!applies) { if (!picked.isEmpty()) throw reject("CATALOG_OPTION_NOT_APPLICABLE"); continue; }
            int count = picked.values().stream().mapToInt(Integer::intValue).sum();
            if (count < g.min() || count > g.max() || (!g.multiple() && picked.size() > 1)) throw reject("CATALOG_SELECTION_COUNT");
            Map<String,Option> values = new HashMap<>(); g.options().forEach(o -> values.put(o.id(),o));
            for (var e : new TreeMap<>(picked).entrySet()) {
                var o = values.get(e.getKey());
                if (o == null || !o.enabled() || e.getValue() > g.maxPerOption()) throw reject("CATALOG_OPTION_UNAVAILABLE");
                extra = extra.add(money(o.surcharge()).multiply(BigDecimal.valueOf(e.getValue())));
                snapshot.add(new Selected(g.id(),g.name(),g.kind(),o.id(),o.name(),e.getValue(),money(o.surcharge())));
            }
        }
        if (!c.groups().stream().map(Group::id).collect(java.util.stream.Collectors.toSet()).containsAll(chosen.keySet()))
            throw reject("CATALOG_UNKNOWN_GROUP");
        return new Quote(extra, List.copyOf(snapshot));
    }
}
