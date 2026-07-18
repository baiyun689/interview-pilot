import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthProvider'
import { ApiClientError } from '../api/request'
import { safeReturnTo } from '../auth/returnTo'

export function RegisterPage() {
  const { register } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [displayName, setDisplayName] = useState('')
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
      await register({ displayName, email, password })
      navigate(safeReturnTo(location.state), { replace: true })
    } catch (error) {
      setMessage(error instanceof ApiClientError ? error.message : '注册失败，请稍后重试')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="auth-page">
      <section className="auth-card" aria-labelledby="register-title">
        <p className="eyebrow">GET STARTED</p>
        <h1 id="register-title">创建账户</h1>
        <p>保存你的简历、练习记录和模型设置。</p>
        <form className="auth-form" onSubmit={submit}>
          <label>称呼<input autoComplete="name" maxLength={100} value={displayName} onChange={(event) => setDisplayName(event.target.value)} required /></label>
          <label>邮箱<input autoComplete="email" type="email" value={email} onChange={(event) => setEmail(event.target.value)} required /></label>
          <label>密码<input autoComplete="new-password" minLength={8} type="password" value={password} onChange={(event) => setPassword(event.target.value)} required /></label>
          {message && <div className="error-notice" role="alert"><p>{message}</p></div>}
          <button className="button button-primary" disabled={submitting} type="submit">{submitting ? '正在创建' : '创建账户'}</button>
        </form>
        <p className="auth-switch">已有账户？<Link state={location.state} to="/login">去登录</Link></p>
      </section>
    </main>
  )
}
