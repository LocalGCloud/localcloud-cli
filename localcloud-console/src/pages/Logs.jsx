import { For, createSignal, createEffect } from 'solid-js';
import { services } from '../api.js';

export default function Logs(props) {
    const [selectedService, setSelectedService] = createSignal(null);
    const [logs, setLogs] = createSignal([]);
    const [loading, setLoading] = createSignal(false);
    const [lineCount, setLineCount] = createSignal(100);
    const [error, setError] = createSignal(null);

    createEffect(() => {
        if (selectedService() && props.services.length > 0) {
            fetchLogs();
        }
    });

    const fetchLogs = async () => {
        if (!selectedService()) return;

        setLoading(true);
        try {
            const result = await services.getLogs(selectedService(), lineCount());
            setLogs(result.lines || []);
            setError(null);
        } catch (err) {
            setError(`Failed to fetch logs: ${err.message}`);
            setLogs([]);
        } finally {
            setLoading(false);
        }
    };

    // Set initial service if available
    createEffect(() => {
        if (!selectedService() && props.services.length > 0) {
            setSelectedService(props.services[0].name);
        }
    });

    return (
        <div>
            <h1>Logs</h1>

            {error() && (
                <div class="alert error">{error()}</div>
            )}

            <div style={{ 'margin-bottom': '16px', 'display': 'flex', 'gap': '12px', 'align-items': 'center' }}>
                <label>
                    <strong>Service:</strong>
                    <select
                        value={selectedService()}
                        onChange={(e) => setSelectedService(e.currentTarget.value)}
                        style={{ 'margin-left': '8px', 'min-width': '150px' }}
                    >
                        <option value="">-- Select service --</option>
                        <For each={props.services}>
                            {(service) => (
                                <option value={service.name}>{service.name}</option>
                            )}
                        </For>
                    </select>
                </label>

                <label>
                    <strong>Lines:</strong>
                    <input
                        type="number"
                        min="10"
                        max="1000"
                        value={lineCount()}
                        onChange={(e) => setLineCount(parseInt(e.currentTarget.value))}
                        style={{ 'margin-left': '8px', 'width': '80px' }}
                    />
                </label>

                <button onClick={fetchLogs} disabled={!selectedService() || loading()}>
                    {loading() ? 'Refreshing...' : 'Refresh'}
                </button>
            </div>

            {/* Logs display */}
            <div class="code-block" style={{ 'height': '500px', 'overflow-y': 'auto', 'white-space': 'pre-wrap', 'word-wrap': 'break-word' }}>
                {loading() && <p>Loading logs...</p>}
                {!loading() && logs().length === 0 && (
                    <p style={{ 'color': 'var(--text-secondary)' }}>No logs available for this service</p>
                )}
                {!loading() && (
                    <For each={logs()}>
                        {(line) => (
                            <div>{line}</div>
                        )}
                    </For>
                )}
            </div>
        </div>
    );
}
