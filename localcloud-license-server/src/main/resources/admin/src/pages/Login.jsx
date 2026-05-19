import { createSignal } from 'solid-js';
import { api } from '../api.js';

export function Login(props) {
  const [password, setPassword] = createSignal('');
  const [error, setError] = createSignal('');
  const [loading, setLoading] = createSignal(false);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const res = await api.login(password());
      props.onLogin(res.token);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div class="login-page">
      <form class="login-box" onSubmit={handleSubmit}>
        <div class="logo-icon">LC</div>
        <h1>License Server</h1>
        <p class="subtitle">Sign in to manage licenses, users, and devices</p>
        <Show when={error()}>
          <div class="error-msg" style="margin-bottom:20px;">{error()}</div>
        </Show>
        <div class="field">
          <label for="admin-password">Admin Password</label>
          <input id="admin-password" type="password" placeholder="Enter your admin password"
                 value={password()} onInput={(e) => setPassword(e.target.value)}
                 autofocus />
        </div>
        <button type="submit" disabled={loading()}>
          {loading() ? 'Signing in...' : 'Sign In'}
        </button>
        <p style="text-align:center;margin-top:20px;font-size:13px;color:var(--text-muted);">
          Authorized administrators only
        </p>
      </form>
    </div>
  );
}
