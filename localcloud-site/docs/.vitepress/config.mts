import { defineConfig } from 'vitepress'

export default defineConfig({
  title: "LocalCloud",
  description: "The local GCP emulator orchestrator for high-fidelity development.",
  themeConfig: {
    logo: '/logo.svg',
    nav: [
      { text: 'Home', link: '/' },
      { text: 'Guide', link: '/guide/getting-started' },
      { text: 'Reference', link: '/guide/service-reference' }
    ],
    sidebar: [
      {
        text: 'Introduction',
        items: [
          { text: 'What is LocalCloud?', link: '/guide/what-is-localcloud' },
          { text: 'Getting Started', link: '/guide/getting-started' }
        ]
      },
      {
        text: 'Core Concepts',
        items: [
          { text: 'Persistence', link: '/guide/persistence' },
          { text: 'Seed Data', link: '/guide/seed-data' },
          { text: 'GCP Credential Bridging', link: '/guide/credential-bridging' }
        ]
      },
      {
        text: 'Services',
        items: [
          { text: 'Storage (GCS)', link: '/guide/services/gcs' },
          { text: 'BigQuery', link: '/guide/services/bigquery' },
          { text: 'Spanner', link: '/guide/services/spanner' },
          { text: 'Pub/Sub', link: '/guide/services/pubsub' },
          { text: 'Firestore', link: '/guide/services/firestore' }
        ]
      }
    ],
    socialLinks: [
      { icon: 'github', link: 'https://github.com/localcloud/localcloud' }
    ],
    footer: {
      message: 'Released under the Apache 2.0 License.',
      copyright: 'Copyright © 2026-present Jay Sen'
    }
  },
  dark: true
})
