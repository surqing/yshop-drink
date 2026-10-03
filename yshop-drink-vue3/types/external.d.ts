export {}

declare global {
  interface UEditorDialog { render(): void; open(): void }
  interface Window {
    _hmt: Array<[string, ...unknown[]]>
    UE: {
      registerUI(name: string, callback: (this: { dialog?: UEditorDialog }, editor: unknown, name: string) => unknown, index: number): void
      ui: {
        Dialog: new (options: Record<string, unknown>) => UEditorDialog
        Button: new (options: Record<string, unknown>) => unknown
      }
    }
  }
}
