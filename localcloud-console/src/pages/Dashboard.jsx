import { For, createEffect, createSignal } from 'solid-js';
import { services } from '../api.js';

export default function Dashboard(props) {
    const [status, setStatus] = createSignal(null);
    const [error, setError] = createSignal(null);

    createEffect(async () => {
        try {
            const result = await services.getStatus();
            setStatus(result);
            setError(null);
        } catch (err) {
            setError(`Failed to fetch status: ${err.message}`);
        }
    });

    return (
        <div>
            <h1>Dashboard</h1>

            {error() && (
                <div class="alert error">{error()}</div>
            )}

            {/* System Status Card */}
            <div class="card" style={{ 'margin-bottom': '20px' }}>
                <div class="card-header">
                    <h2>System Status</h2>
                </div>
                <div class="card-body">
                    {status() ? (
                        <>
                            <div style={{ 'margin-bottom': '12px' }}>
                                <strong>Version:</strong> {status().version || 'N/A'}
                            </div>
                            <div style={{ 'margin-bottom': '12px' }}>
                                <strong>Uptime:</strong> {status().uptime || 'N/A'}
                            </div>
                            <div>
                                <strong>Health:</strong> <span class="status-badge running">HEALTHY</span>
                            </div>
                        </>
                    ) : (
                        <p>Loading...</p>
                    )}
                </div>
            </div>

            {/* Services Overview */}
            <h2 style={{ 'margin-top': '24px', 'margin-bottom': '12px' }}>Services</h2>
            <div class="grid cols-3">
                {props.loading() && <p>Loading services...</p>}
                {!props.loading() && props.services.length === 0 && (
                    <p style={{ 'color': 'var(--text-secondary)' }}>No services available</p>
                )}
                <For each={props.services}>
                    {(service) => (
                        <div class="card">
                            <div class="service-name">{service.name}</div>
                            <div class="service-info">
                                <span>Port: {service.port || 'N/A'}</span>
                                <span class={`status-badge ${service.status === 'running' ? 'running' : 'stopped'}`}>
                                    {service.status || 'UNKNOWN'}
                                </span>
                            </div>
                        </div>
                    )}
                </For>
            </div>

            {/* Quick Actions */}
            <div class="card" style={{ 'margin-top': '24px' }}>
                <div class="card-header">
                    <h2>Quick Actions</h2>
                </div>
                <div class="service-actions">
                    <button class="secondary" onClick={() => window.location.reload()}>Refresh</button>
                    <button class="danger" onClick={() => {
                        if (confirm('Are you sure you want to reset all services?')) {
                            services.reset().then(() => {
                                alert('Services reset successfully');
                                window.location.reload();
                            }).catch(err => alert(`Error: ${err.message}`));
                        }
                    }}>Reset All</button>
                </div>
            </div>
        </div>
    );
}
