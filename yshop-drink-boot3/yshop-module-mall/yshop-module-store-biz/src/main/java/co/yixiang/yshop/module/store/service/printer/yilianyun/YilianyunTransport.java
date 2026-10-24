package co.yixiang.yshop.module.store.service.printer.yilianyun;

import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;

/** Allows HTTP contract tests with a loopback server without changing production endpoints. */
public interface YilianyunTransport {
    JsonNode post(String path,Map<String,String> form);
}
