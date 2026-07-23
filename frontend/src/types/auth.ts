export interface AuthenticatedUser {
  userId: string
  email: string
  displayName: string
}

export interface LoginInput {
  email: string
  password: string
}

export interface RegisterInput extends LoginInput {
  displayName: string
}
