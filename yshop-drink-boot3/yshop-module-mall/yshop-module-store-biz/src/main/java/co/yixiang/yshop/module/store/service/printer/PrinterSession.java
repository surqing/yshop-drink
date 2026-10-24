package co.yixiang.yshop.module.store.service.printer;

import java.util.Map;
import static co.yixiang.yshop.module.store.service.printer.PrinterModels.*;

/** Prepared provider connection. Never expose credentials or acquire another DB connection. */
public interface PrinterSession {
    void authorize(String deviceCode, String name, Map<String, String> configuration);
    void deauthorize(String deviceCode);
    DeviceState status(String deviceCode);
    PrintResult print(String deviceCode, String requestId, Receipt receipt);
}
