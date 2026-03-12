import { For, createSignal } from 'solid-js';
import { services } from '../api.js';

export default function Services(props) {
    const [loading, setLoading] = createSignal({});
    const [error, setError] = createSignal(null);

    const toggleService = async (serviceName, currentStatus) => {
        const isRunning = currentStatus === 'running';
        const action = isRunning ? 'stop' : 'start';

        setLoading(prev => ({ ...prev, [serviceName]: true }));
        try {
            if (isRunning) {
                await services.stopService(serviceName);
            } else {
                await services.startService(serviceName);
            }
            setError(null);
            // Refresh the services list
            window.location.reload();
        } catch (err) {
            setError(`Failed to ${action} ${serviceName}: ${err.message}`);
        } finally {
            setLoading(prev => ({ ...prev, [serviceName]: false }));
        }
    };

    return (
        <div>
            <h1>Services</h1>

            {error() && (
                <div class="alert error">{error()}</div>
            )}

            <div style={{ 'margin-bottom': '16px' }}>
                <p style={{ 'color': 'var(--text-secondary)' }}>
                    Manage LocalCloud services. Click a service to start or stop it.
                </p>
            </div>

            {props.loading() && <p>Loading services...</p>}

            <table class="table">
                <thead>
                    <tr>
                        <th>Service Name</th>
                        <th>Status</th>
                        <th>Port</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <For each={props.services}>
                        {(service) => (
                            <tr>
                                <td><strong>{service.name}</strong></td>
                                <td>
                                    <span class={`status-badge ${service.status === 'running' ? 'running' : service.status === 'error' ? 'error' : 'stopped'}`}>
                                        {service.status || 'UNKNOWN'}
                                    </span>
                                </td>
                                <td>{service.port || 'N/A'}</td>
                                <td>
                                    <button
                                        class={service.status === 'running' ? 'danger' : ''}
                                        disabled={loading()[service.name]}
                                        onClick={() => toggleService(service.name, service.status)}
                                    >
                                        {loading()[service.name] ? 'Loading...' : service.status === 'running' ? 'Stop' : 'Start'}
                                    </button>
                                </td>
                            </tr>
                        )}
                    </For>
                </tbody>
            </table>

            {!props.loading() && props.services.length === 0 && (
                <div class="alert info">No services found</div>
            )}
        </div>
    );
}
