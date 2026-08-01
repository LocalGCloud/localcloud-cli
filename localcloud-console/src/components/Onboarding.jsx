import { createSignal, For, onCleanup } from 'solid-js';
import { trapFocus } from '../utils/a11y.js';

const STEPS = [
    {
        title: 'Welcome to LocalCloud',
        body: 'Run Google Cloud services locally for development and testing. Zero config, zero cost.',
        icon: 'M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5',
    },
    {
        title: 'Pick a service',
        body: 'Use the sidebar to browse services. Start with Spanner or BigQuery — they have full SQL support.',
        icon: 'M4 6h16M4 12h16M4 18h16',
    },
    {
        title: 'Try the SQL editor',
        body: 'Click a database service, then use the SQL Editor tab. Run queries with ⌘Enter.',
        icon: 'M9.4 16.6L4.8 12l4.6-4.6L8 6l-6 6 6 6 1.4-1.4zm5.2 0l4.6-4.6-4.6-4.6L16 6l6 6-6 6-1.4-1.4z',
    },
];

export default function Onboarding(props) {
    const [step, setStep] = createSignal(0);

    const finish = () => {
        try { localStorage.setItem('localcloud-onboarding-complete', 'true'); } catch {}
        props.onDone();
    };

    const next = () => {
        if (step() >= STEPS.length - 1) finish();
        else setStep(s => s + 1);
    };

    return (
        <div class="modal-overlay"
            role="dialog" aria-modal="true" aria-label="Welcome to LocalCloud"
            onClick={(e) => { if (e.target === e.currentTarget) finish(); }}>
            <div class="create-dialog"
                onClick={(e) => e.stopPropagation()}
                ref={(el) => {
                    let cleanup;
                    const frame = requestAnimationFrame(() => {
                        cleanup = trapFocus(el, finish);
                    });
                    onCleanup(() => {
                        cancelAnimationFrame(frame);
                        cleanup?.();
                    });
                }}
                style={{ width: '420px', padding: '0', display: 'flex', 'flex-direction': 'column' }}>
                <div class="create-dialog-accent" style="background:var(--primary)" />
                <div class="create-dialog-header" style={{ 'text-align': 'center', 'padding-bottom': '8px' }}>
                    <div class="create-dialog-header-icon" style={{
                        color: 'var(--primary)',
                        'border-color': 'var(--primary-soft)',
                        background: 'var(--primary-soft)',
                        margin: '0 auto 12px',
                    }}>
                        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <path d={STEPS[step()].icon} />
                        </svg>
                    </div>
                    <h2 class="create-dialog-title" style={{ margin: 0 }}>{STEPS[step()].title}</h2>
                    <p class="create-dialog-context" style={{ 'margin-top': '6px', 'max-width': '320px', margin: '6px auto 0' }}>
                        {STEPS[step()].body}
                    </p>
                </div>
                <div style={{ padding: '16px 28px 20px', display: 'flex', 'align-items': 'center', 'justify-content': 'space-between' }}>
                    <div style={{ display: 'flex', gap: '6px' }}>
                        <For each={STEPS}>
                            {(_, i) => (
                                <span style={{
                                    width: '8px',
                                    height: '8px',
                                    'border-radius': '999px',
                                    background: i() === step() ? 'var(--primary)' : 'var(--border)',
                                    transition: 'background 200ms ease',
                                    display: 'inline-block',
                                }} />
                            )}
                        </For>
                    </div>
                    <div style={{ display: 'flex', gap: '8px' }}>
                        <button class="create-dialog-btn-cancel" onClick={finish}>Skip</button>
                        <button class="create-dialog-btn-submit" style="background:var(--primary);color:#fff"
                            onClick={next}>
                            {step() >= STEPS.length - 1 ? 'Got it' : 'Next'}
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
}
