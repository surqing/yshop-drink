package co.yixiang.yshop.module.store.service.printer;

/** Safe local error codes only: never retain provider responses or secrets in exceptions. */
public final class PrinterFailure extends RuntimeException {
    private final boolean uncertain;
    public PrinterFailure(String code, boolean uncertain) { super(code); this.uncertain = uncertain; }
    public boolean uncertain() { return uncertain; }
}
