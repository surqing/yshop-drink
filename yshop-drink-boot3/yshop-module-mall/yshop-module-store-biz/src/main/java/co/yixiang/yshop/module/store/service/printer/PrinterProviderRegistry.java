package co.yixiang.yshop.module.store.service.printer;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class PrinterProviderRegistry {
    private final Map<String, PrinterProvider> providers;
    public PrinterProviderRegistry(List<PrinterProvider> adapters) {
        Map<String, PrinterProvider> entries = new LinkedHashMap<>();
        for (PrinterProvider adapter : adapters) {
            if (entries.putIfAbsent(adapter.info().code(), adapter) != null)
                throw new IllegalStateException("DUPLICATE_PRINTER_PROVIDER");
        }
        providers = Collections.unmodifiableMap(entries);
    }
    public PrinterProvider require(String code) {
        PrinterProvider provider = providers.get(code);
        if (provider == null) throw new PrinterFailure("UNSUPPORTED_PROVIDER", false);
        return provider;
    }
    public List<PrinterModels.ProviderInfo> list() {
        return providers.values().stream().map(PrinterProvider::info).toList();
    }
}
