/**
 * ServiceCard Component
 * Displays a single service with its status, port, and details in a card layout
 * Used in Dashboard and Services pages
 */

import { StatusBadge } from './StatusBadge';

export function ServiceCard(props) {
  return (
    <div class="service-card">
      <div class="card-header">
        <h3>{props.service.name}</h3>
        <StatusBadge status={props.service.status} />
      </div>

      <div class="card-body">
        <div class="service-detail">
          <span class="label">Service ID:</span>
          <span class="value">{props.service.id}</span>
        </div>

        <div class="service-detail">
          <span class="label">Protocol:</span>
          <span class="value">{props.service.protocol?.toUpperCase() || 'REST'}</span>
        </div>

        <div class="service-detail">
          <span class="label">Port:</span>
          <span class="value">{props.service.port}</span>
        </div>

        <div class="service-detail">
          <span class="label">Endpoint:</span>
          <span class="value endpoint">
            <code>{props.service.endpoint || `http://localhost:${props.service.port}`}</code>
          </span>
        </div>

        {props.service.env_var && (
          <div class="service-detail">
            <span class="label">Env Var:</span>
            <span class="value env-var">
              <code>{props.service.env_var}={props.service.env_value}</code>
            </span>
          </div>
        )}

        {props.service.request_count !== undefined && (
          <div class="service-detail">
            <span class="label">Requests:</span>
            <span class="value">{props.service.request_count}</span>
          </div>
        )}
      </div>

      {props.onRefresh && (
        <div class="card-footer">
          <button
            class="btn btn-sm btn-primary"
            onclick={() => props.onRefresh(props.service.id)}
          >
            Refresh
          </button>
        </div>
      )}
    </div>
  );
}
