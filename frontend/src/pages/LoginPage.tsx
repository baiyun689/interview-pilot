import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthProvider'
import { ApiClientError } from '../api/request'
import { safeReturnTo } from '../auth/returnTo'

export function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [message, setMessage] = useState<string | null>(null)

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting) return
    setSubmitting(true)
    setMessage(null)
    try {
      await login({ email, password })
      navigate(safeReturnTo(location.state), { replace: true })
    } catch (error) {
      setMessage(error instanceof ApiClientError ? error.message : '登录失败，请稍后重试')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="auth-page">
      <section className="auth-card" aria-labelledby="login-title">
        <p className="eyebrow">WELCOME BACK</p>
        <h1 id="login-title">登录</h1>
        <p>登录后继续准备下一场面试。</p>
        <form className="auth-form" onSubmit={submit}>
          <label>邮箱<input autoComplete="email" type="email" value={email} onChange={(event) => setEmail(event.target.value)} required /></label>
          <label>密码<input autoComplete="current-password" type="password" value={password} onChange={(event) => setPassword(event.target.value)} required /></label>
          {message && <div className="error-notice" role="alert"><p>{message}</p></div>}
          <button className="button button-primary" disabled={submitting} type="submit">{submitting ? '正在登录' : '登录'}</button>
        </form>
        <p className="auth-switch">还没有账户？<Link state={location.state} to="/register">创建账户</Link></p>
      </section>
    </main>
  )
}
