export interface AuthenticatedUser {
  userId: string
  email: string
  displayName: string
}

export interface LoginInput {
  email: string
  password: string
}

export interface RegisterInput {
  email: string
  password: string
  displayName: string
}

export interface TokenPairResponse {
  accessToken: string
  userId: string
  email: string
  displayName: string
}
