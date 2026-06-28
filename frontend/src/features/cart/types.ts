export interface CartItem {
  id: number
  title: string
  image: string
  price: number
}

export interface CartItemApiResponse {
  cartId: number
  productId: number
  productName: string
  price: number
  isSoldOut: boolean
}
