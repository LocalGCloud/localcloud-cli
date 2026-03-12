/**
 * Header Component
 * Displays the app title, project ID, and dark/light theme toggle
 */

import { Show } from 'solid-js';

export function Header(props) {
  return (
    <header class="app-header">
      <div class="header-content">
        <div class="header-left">
          <h1 class="app-title">LocalCloud Dashboard</h1>
          <Show when={props.projectId}>
            <span class="project-badge">{props.projectId}</span>
          </Show>
        </div>

        <div class="header-right">
          <div class="theme-toggle">
            <button
              class={`theme-btn ${props.darkMode ? 'active' : ''}`}
              onclick={props.onThemeToggle}
              title="Toggle dark/light mode"
            >
              {props.darkMode ? '☀️' : '🌙'}
            </button>
          </div>
        </div>
      </div>
    </header>
  );
}
