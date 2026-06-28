import { apiClient } from '@/shared/api/axios'
import type { CartItemApiResponse } from './types'

export const cartApi = {
  addItem: async (productId: number): Promise<CartItemApiResponse> => {
    const res = await apiClient.post<{ data: CartItemApiResponse }>('/cart-items', { productId })
    return res.data.data
  },
}
