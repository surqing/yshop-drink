const PRINTER_COMPONENTS = ['mall/store/printer/index', 'store/webPrint/index']
export function canManagePrinters(shopId: unknown, permissions: string[]): boolean {
  return shopId === 0 && permissions.some((p) => p === '*:*:*' || p === 'store:web-print:query')
}
export function filterPrinterMenus(
  routes: AppCustomRouteRecordRaw[],
  allowed: boolean
): AppCustomRouteRecordRaw[] {
  return routes
    .filter(
      (route) => allowed || !PRINTER_COMPONENTS.includes((route.component || '').replace(/^\//, ''))
    )
    .map((route) => ({
      ...route,
      ...(route.children ? { children: filterPrinterMenus(route.children, allowed) } : {})
    }))
}
export function hasPrinterMenu(routes: AppCustomRouteRecordRaw[]): boolean {
  return routes.some(
    (route) =>
      (route.component || '').replace(/^\//, '') === PRINTER_COMPONENTS[0] ||
      (!!route.children && hasPrinterMenu(route.children))
  )
}
