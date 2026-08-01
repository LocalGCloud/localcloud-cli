import { Show } from 'solid-js';

export function ErrorAlert(props) {
    return (
        <Show when={props.message}>
            <div class="alert alert-error" role={props.role} style={props.style}>
                {props.message}
            </div>
        </Show>
    );
}

export function LoadingShield(props) {
    return (
        <Show
            when={!props.loading}
            fallback={
                <div class="loading-state" role={props.role} style={props.style}>
                    <div class="loading-spinner" />
                </div>
            }
        >
            {props.children}
        </Show>
    );
}
