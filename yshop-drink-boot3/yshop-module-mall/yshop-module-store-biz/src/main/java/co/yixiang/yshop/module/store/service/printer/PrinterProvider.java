package co.yixiang.yshop.module.store.service.printer;

import java.util.Map;
import static co.yixiang.yshop.module.store.service.printer.PrinterModels.*;

/** Brand and transport boundary; no vendor commands escape this interface. */
public interface PrinterProvider {
    ProviderInfo info();
    void validate(String deviceCode, String connectionType, Map<String, String> configuration);
    void authorize(String deviceCode, String name, Map<String, String> configuration);
    void deauthorize(String deviceCode);
    DeviceState status(String deviceCode);
    PrintResult print(String deviceCode, String requestId, Receipt receipt);
}
