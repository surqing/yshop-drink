package co.yixiang.yshop.module.store.service.printer;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.*;

/** Provider-independent public contracts. Credentials are accepted only on writes. */
public final class PrinterModels {
    private PrinterModels() {}
    public enum DeviceState { ONLINE, OFFLINE, PAPER_OUT, UNKNOWN }
    public enum Authorization { UNAUTHORIZED, AUTHORIZED, UNKNOWN }
    public enum JobState { SUBMITTING, ACCEPTED, FAILED, UNKNOWN }
    public record Field(String key, String label, boolean secret, boolean required) {}
    public record Capabilities(boolean authorize, boolean deauthorize, boolean status, boolean textPrint) {}
    public record ProviderInfo(String code, String name, List<String> connectionTypes,
                               List<Field> configFields, Capabilities capabilities, boolean configured) {}
    public record SaveRequest(Long id, @NotBlank @Size(max=80) String name,
            @NotBlank @Size(max=40) String providerCode, @NotBlank @Size(max=100) String deviceCode,
            @Size(max=80) String model, @NotBlank String connectionType, @NotNull Boolean enabled,
            @NotNull @Size(max=10) Map<@Size(max=40) String, @Size(max=500) String> configuration) {
        @Override public String toString() { return "PrinterSaveRequest[id="+id+", configuration=REDACTED]"; }
    }
    public record BindingRequest(@NotNull Long id, @NotNull Long shopId) {}
    public record TestRequest(@NotNull Long id,
            @NotBlank @Pattern(regexp="[a-zA-Z0-9_-]{16,64}") String requestId) {}
    public record ShopOption(Long id, String name) {}
    public record DeviceView(Long id, String name, String providerCode, String deviceCode, String model,
            String connectionType, boolean enabled, Authorization authorization, DeviceState deviceState,
            LocalDateTime stateCheckedAt, Long shopId, String shopName, Set<String> configuredFields,
            String lastError, LocalDateTime createTime) {}
    public record JobView(String requestId, Long printerId, String printerName, JobState state,
            String providerJobId, String errorCode, LocalDateTime createTime) {}
    public record OperationResult(Authorization authorization, String errorCode) {}
    public record StatusResult(DeviceState state, LocalDateTime checkedAt, String errorCode) {}
    public record Receipt(String title, List<String> lines, int copies) {
        public Receipt { lines = List.copyOf(lines); }
    }
    public record PrintResult(String providerJobId) {}
}
