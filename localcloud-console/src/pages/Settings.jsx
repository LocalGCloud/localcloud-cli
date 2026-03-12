export default function Settings(props) {
    return (
        <div>
            <h1>Settings</h1>

            <div class="card" style={{ 'margin-bottom': '20px' }}>
                <div class="card-header">
                    <h2>Display</h2>
                </div>
                <div class="card-body">
                    <div style={{ 'margin-bottom': '16px' }}>
                        <label style={{ 'display': 'flex', 'align-items': 'center', 'gap': '8px', 'cursor': 'pointer' }}>
                            <input
                                type="checkbox"
                                checked={props.darkMode()}
                                onChange={(e) => props.setDarkMode(e.currentTarget.checked)}
                            />
                            <strong>Dark Mode</strong>
                        </label>
                        <p style={{ 'color': 'var(--text-secondary)', 'font-size': '12px', 'margin-top': '4px' }}>
                            Use dark theme for the console
                        </p>
                    </div>
                </div>
            </div>

            <div class="card" style={{ 'margin-bottom': '20px' }}>
                <div class="card-header">
                    <h2>Refresh Interval</h2>
                </div>
                <div class="card-body">
                    <label>
                        <strong>Auto-refresh interval (ms):</strong>
                        <input
                            type="number"
                            min="1000"
                            max="60000"
                            step="1000"
                            value={props.refreshInterval()}
                            onChange={(e) => props.refreshInterval(parseInt(e.currentTarget.value))}
                            style={{ 'margin-left': '8px', 'width': '100px' }}
                        />
                    </label>
                    <p style={{ 'color': 'var(--text-secondary)', 'font-size': '12px', 'margin-top': '4px' }}>
                        How often to refresh service status (in milliseconds)
                    </p>
                </div>
            </div>

            <div class="card">
                <div class="card-header">
                    <h2>About</h2>
                </div>
                <div class="card-body">
                    <div style={{ 'margin-bottom': '8px' }}>
                        <strong>LocalCloud Console</strong>
                    </div>
                    <p style={{ 'color': 'var(--text-secondary)' }}>
                        A lightweight web console for managing LocalCloud GCP emulator services.
                    </p>
                    <div style={{ 'margin-top': '12px' }}>
                        <strong>Version:</strong> 0.1.0
                    </div>
                </div>
            </div>
        </div>
    );
}
