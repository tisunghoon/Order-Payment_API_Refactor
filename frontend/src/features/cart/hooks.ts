import { useMutation } from '@tanstack/react-query'

import { cartApi } from './api'
import { useCartStore } from './store'

export function useCart() {
  return useCartStore((s) => s.items)
}

export function useCartCount() {
  return useCartStore((s) => s.items.length)
}

export function useAddCartItem() {
  return useMutation({
    mutationFn: (productId: number) => cartApi.addItem(productId),
  })
}
