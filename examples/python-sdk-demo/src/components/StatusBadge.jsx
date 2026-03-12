/**
 * StatusBadge Component
 * Displays a colored status indicator with text
 * Used to show service health status (running, stopped, error)
 */

export function StatusBadge(props) {
  // Determine CSS class based on status
  const getStatusClass = () => {
    switch (props.status) {
      case 'running':
      case 'healthy':
        return 'status-badge status-running';
      case 'stopped':
      case 'offline':
        return 'status-badge status-stopped';
      case 'error':
      case 'unhealthy':
        return 'status-badge status-error';
      default:
        return 'status-badge status-unknown';
    }
  };

  // Format status text for display
  const getStatusText = () => {
    switch (props.status) {
      case 'running':
        return 'Running';
      case 'stopped':
        return 'Stopped';
      case 'error':
        return 'Error';
      case 'healthy':
        return 'Healthy';
      case 'offline':
        return 'Offline';
      case 'unhealthy':
        return 'Unhealthy';
      default:
        return props.status;
    }
  };

  return (
    <span class={getStatusClass()} title={`Status: ${getStatusText()}`}>
      <span class="status-dot"></span>
      {getStatusText()}
    </span>
  );
}
