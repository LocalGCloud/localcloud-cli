import { For, createSignal, createEffect } from 'solid-js';
import { data } from '../api.js';

export default function DataBrowser(props) {
    const [dataType, setDataType] = createSignal('firestore');
    const [items, setItems] = createSignal([]);
    const [loading, setLoading] = createSignal(false);
    const [error, setError] = createSignal(null);

    createEffect(() => {
        fetchData();
    });

    const fetchData = async () => {
        setLoading(true);
        setError(null);
        try {
            let result;
            switch (dataType()) {
                case 'firestore':
                    result = await data.getFirestoreCollections();
                    setItems(result.collections || []);
                    break;
                case 'bigquery':
                    result = await data.getBigQueryDatasets();
                    setItems(result.datasets || []);
                    break;
                case 'gcs':
                    result = await data.getGCSBuckets();
                    setItems(result.buckets || []);
                    break;
                default:
                    setItems([]);
            }
        } catch (err) {
            setError(`Failed to fetch ${dataType()}: ${err.message}`);
            setItems([]);
        } finally {
            setLoading(false);
        }
    };

    return (
        <div>
            <h1>Data Browser</h1>

            {error() && (
                <div class="alert error">{error()}</div>
            )}

            <div style={{ 'margin-bottom': '16px', 'display': 'flex', 'gap': '12px' }}>
                <label>
                    <strong>Data Source:</strong>
                    <select
                        value={dataType()}
                        onChange={(e) => setDataType(e.currentTarget.value)}
                        style={{ 'margin-left': '8px', 'min-width': '150px' }}
                    >
                        <option value="firestore">Firestore</option>
                        <option value="bigquery">BigQuery</option>
                        <option value="gcs">Google Cloud Storage</option>
                    </select>
                </label>
                <button onClick={fetchData} disabled={loading()}>
                    {loading() ? 'Loading...' : 'Refresh'}
                </button>
            </div>

            {/* Data display based on type */}
            <div>
                {loading() && <p>Loading {dataType()} data...</p>}

                {!loading() && items().length === 0 && (
                    <div class="alert info">
                        No {dataType()} data found. Create some data to see it here.
                    </div>
                )}

                {!loading() && items().length > 0 && (
                    <div>
                        {dataType() === 'firestore' && (
                            <div>
                                <h2>Collections ({items().length})</h2>
                                <table class="table">
                                    <thead>
                                        <tr>
                                            <th>Name</th>
                                            <th>Documents</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        <For each={items()}>
                                            {(collection) => (
                                                <tr>
                                                    <td><strong>{collection.name}</strong></td>
                                                    <td>{collection.count || 0}</td>
                                                </tr>
                                            )}
                                        </For>
                                    </tbody>
                                </table>
                            </div>
                        )}

                        {dataType() === 'bigquery' && (
                            <div>
                                <h2>Datasets ({items().length})</h2>
                                <table class="table">
                                    <thead>
                                        <tr>
                                            <th>Name</th>
                                            <th>Tables</th>
                                            <th>Created</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        <For each={items()}>
                                            {(dataset) => (
                                                <tr>
                                                    <td><strong>{dataset.name}</strong></td>
                                                    <td>{dataset.tables || 0}</td>
                                                    <td>{dataset.created || 'N/A'}</td>
                                                </tr>
                                            )}
                                        </For>
                                    </tbody>
                                </table>
                            </div>
                        )}

                        {dataType() === 'gcs' && (
                            <div>
                                <h2>Buckets ({items().length})</h2>
                                <table class="table">
                                    <thead>
                                        <tr>
                                            <th>Name</th>
                                            <th>Objects</th>
                                            <th>Created</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        <For each={items()}>
                                            {(bucket) => (
                                                <tr>
                                                    <td><strong>{bucket.name}</strong></td>
                                                    <td>{bucket.objects || 0}</td>
                                                    <td>{bucket.created || 'N/A'}</td>
                                                </tr>
                                            )}
                                        </For>
                                    </tbody>
                                </table>
                            </div>
                        )}
                    </div>
                )}
            </div>
        </div>
    );
}
