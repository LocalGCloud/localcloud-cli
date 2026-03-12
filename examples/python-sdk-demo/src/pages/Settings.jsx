/**
 * Settings Page
 * User preferences including:
 * - Dark mode toggle
 * - Auto-refresh interval
 * - Log retention
 * - Environment variable export
 */

import { createSignal, Show } from 'solid-js';

export function Settings(props) {
  const [refreshInterval, setRefreshInterval] = createSignal(props.refreshInterval || 5);
  const [darkMode, setDarkMode] = createSignal(props.darkMode || false);
  const [autoTailLogs, setAutoTailLogs] = createSignal(true);
  const [copiedText, setCopiedText] = createSignal('');

  const handleSaveSettings = () => {
    // Notify parent component of settings changes
    if (props.onSettingsChange) {
      props.onSettingsChange({
        refreshInterval: refreshInterval(),
        darkMode: darkMode(),
        autoTailLogs: autoTailLogs()
      });
    }

    // Show temporary success message
    setCopiedText('Settings saved!');
    setTimeout(() => setCopiedText(''), 2000);
  };

  const handleExportEnv = (format) => {
    if (props.onExportEnv) {
      props.onExportEnv(format);
    }
  };

  const copyToClipboard = async (text) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedText('Copied!');
      setTimeout(() => setCopiedText(''), 2000);
    } catch (err) {
      console.error('Failed to copy:', err);
    }
  };

  return (
    <div class="page settings-page">
      <div class="page-header">
        <h2>Settings</h2>
        <p class="subtitle">Configure Dashboard & Environment</p>
      </div>

      {/* Display Settings */}
      <div class="settings-section">
        <h3>Display Settings</h3>

        <div class="setting-item">
          <label for="dark-mode-toggle" class="setting-label">
            <span class="label-text">Dark Mode</span>
            <input
              id="dark-mode-toggle"
              type="checkbox"
              checked={darkMode()}
              onchange={(e) => setDarkMode(e.target.checked)}
              class="checkbox"
            />
          </label>
          <p class="setting-description">Toggle between light and dark theme</p>
        </div>

        <div class="setting-item">
          <label for="refresh-interval" class="setting-label">
            <span class="label-text">Auto-Refresh Interval</span>
          </label>
          <div class="setting-input">
            <input
              id="refresh-interval"
              type="number"
              min="1"
              max="60"
              value={refreshInterval()}
              onchange={(e) => setRefreshInterval(parseInt(e.target.value))}
              class="input-field"
            />
            <span class="unit">seconds</span>
          </div>
          <p class="setting-description">How often to refresh service status and logs</p>
        </div>

        <div class="setting-item">
          <label for="auto-tail-logs" class="setting-label">
            <span class="label-text">Auto-Tail Logs</span>
            <input
              id="auto-tail-logs"
              type="checkbox"
              checked={autoTailLogs()}
              onchange={(e) => setAutoTailLogs(e.target.checked)}
              class="checkbox"
            />
          </label>
          <p class="setting-description">Automatically update request logs</p>
        </div>

        <div class="setting-actions">
          <button class="btn btn-primary" onclick={handleSaveSettings}>
            Save Settings
          </button>
          <Show when={copiedText()}>
            <span class="success-message">{copiedText()}</span>
          </Show>
        </div>
      </div>

      {/* Environment Variables Export */}
      <div class="settings-section">
        <h3>Environment Variables</h3>
        <p class="section-description">
          Export environment variables for your application to use LocalCloud emulated services.
        </p>

        <div class="export-options">
          <div class="export-format">
            <h4>Shell Format</h4>
            <p>Copy and paste into your terminal with: <code>eval $(localcloud env --format=shell)</code></p>
            <button
              class="btn btn-secondary"
              onclick={() => handleExportEnv('shell')}
            >
              Export Shell Variables
            </button>
          </div>

          <div class="export-format">
            <h4>Docker Compose Format</h4>
            <p>Use in docker-compose.override.yml for multi-container setups</p>
            <button
              class="btn btn-secondary"
              onclick={() => handleExportEnv('docker-compose')}
            >
              Export Docker Compose
            </button>
          </div>

          <div class="export-format">
            <h4>JSON Format</h4>
            <p>Integrate with your build tools and CI/CD pipelines</p>
            <button
              class="btn btn-secondary"
              onclick={() => handleExportEnv('json')}
            >
              Export JSON
            </button>
          </div>
        </div>
      </div>

      {/* Quick Reference */}
      <div class="settings-section">
        <h3>Quick Reference</h3>

        <div class="info-box">
          <h4>Environment Variable Names</h4>
          <table class="reference-table">
            <thead>
              <tr>
                <th>Service</th>
                <th>Environment Variable</th>
                <th>Value</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>Cloud Storage</td>
                <td><code>STORAGE_EMULATOR_HOST</code></td>
                <td><code>http://localhost:8080</code></td>
              </tr>
              <tr>
                <td>Pub/Sub</td>
                <td><code>PUBSUB_EMULATOR_HOST</code></td>
                <td><code>localhost:9020</code></td>
              </tr>
              <tr>
                <td>Firestore</td>
                <td><code>FIRESTORE_EMULATOR_HOST</code></td>
                <td><code>localhost:9010</code></td>
              </tr>
              <tr>
                <td>BigQuery</td>
                <td><code>BIGQUERY_EMULATOR_HOST</code></td>
                <td><code>localhost:8080</code></td>
              </tr>
              <tr>
                <td>Secret Manager</td>
                <td><code>GOOGLE_CLOUD_UNIVERSE_DOMAIN</code></td>
                <td><code>localhost:8080</code></td>
              </tr>
              <tr>
                <td>Cloud Tasks</td>
                <td><code>CLOUDTASKS_EMULATOR_HOST</code></td>
                <td><code>localhost:8080</code></td>
              </tr>
              <tr>
                <td>Spanner</td>
                <td><code>SPANNER_EMULATOR_HOST</code></td>
                <td><code>localhost:9030</code></td>
              </tr>
              <tr>
                <td>Bigtable</td>
                <td><code>BIGTABLE_EMULATOR_HOST</code></td>
                <td><code>localhost:9040</code></td>
              </tr>
              <tr>
                <td>All Services</td>
                <td><code>GOOGLE_CLOUD_PROJECT</code></td>
                <td><code>local-project</code></td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="info-box">
          <h4>Usage Example (Python)</h4>
          <pre class="code-block">
            <code>
{`import os
from google.cloud import storage

# Set environment variable
os.environ['STORAGE_EMULATOR_HOST'] = 'http://localhost:8080'

# Use standard Google Cloud SDK
client = storage.Client()
bucket = client.bucket('my-bucket')
# Your code here...`}
            </code>
          </pre>
        </div>

        <div class="info-box">
          <h4>Usage Example (Node.js)</h4>
          <pre class="code-block">
            <code>
{`const {Storage} = require('@google-cloud/storage');

// Set environment variable
process.env.STORAGE_EMULATOR_HOST = 'http://localhost:8080';

const storage = new Storage({projectId: 'local-project'});
const bucket = storage.bucket('my-bucket');
// Your code here...`}
            </code>
          </pre>
        </div>
      </div>

      {/* Documentation Links */}
      <div class="settings-section">
        <h3>Documentation</h3>
        <ul class="doc-links">
          <li>
            <a href="https://github.com/localcloud/localcloud#readme" target="_blank">
              LocalCloud README
            </a>
          </li>
          <li>
            <a href="https://cloud.google.com/docs" target="_blank">
              Google Cloud Documentation
            </a>
          </li>
          <li>
            <a href="https://github.com/googleapis/python-storage" target="_blank">
              Cloud Storage Python Client
            </a>
          </li>
        </ul>
      </div>
    </div>
  );
}
