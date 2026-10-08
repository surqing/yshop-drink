import request from '@/config/axios'
export interface CatalogOption { id: string; name: string; surcharge: number | string; enabled: boolean; defaultQuantity: number }
export interface CatalogGroup { id: string; name: string; kind: 'CUSTOM' | 'TOPPING'; multiple: boolean; min: number; max: number; maxPerOption: number; enabled: boolean; when: { groupId: string; optionId: string } | null; options: CatalogOption[] }
export interface CatalogConfig { groups: CatalogGroup[] }
export interface CatalogSku { id: number; sku: string; price: number | string; stock: number; is_show: number }
export interface CatalogData { version: number; configuration: CatalogConfig; skus: CatalogSku[] }
export const configuration = (productId: number): Promise<CatalogData> => request.get({ url: '/product/catalog/configuration', params: { productId } })
export const configure = (productId: number, version: number, configuration: CatalogConfig) => request.put({ url: '/product/catalog/configuration', data: { productId, version, configuration } })
export const stock = (data: { productId: number; skuId: number; expected: number; delta: number; reason: string; key: string }) => request.post({ url: '/product/catalog/stock', data })
export const price = (data: { productId: number; skuId: number; price: number | string; version: number; reason: string; key: string }) => request.put({ url: '/product/catalog/price', data })
export const skuSale = (data: { productId: number; skuId: number; enabled: boolean; version: number }) => request.put({ url: '/product/catalog/sku-sale', data })
export const history = (productId: number): Promise<Record<string, unknown>[]> => request.get({ url: '/product/catalog/history', params: { productId } })
export const copy = (data: { sourceId: number; targetShopId: number; targetCategoryId: number; key: string }): Promise<number> => request.post({ url: '/product/catalog/copy', data })
export const batch = (ids: number[], sale?: number, categoryId?: number) => request.post({ url: '/product/catalog/batch', data: { ids, sale, categoryId } })
