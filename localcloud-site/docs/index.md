---
layout: home

hero:
  name: "LocalCloud"
  text: "GCP Fidelity. Local Velocity."
  tagline: "The open-source GCP emulator orchestrator for local development and CI/CD."
  image:
    src: /logo.png
    alt: LocalCloud
  actions:
    - theme: brand
      text: Get Started
      link: /guide/getting-started
    - theme: alt
      text: View on GitHub
      link: https://github.com/localcloud/localcloud

features:
  - icon: 🚀
    title: Instant Setup
    details: Launch 10+ GCP services with a single Docker command. No cloud credentials required.
  - icon: 💾
    title: Real Persistence
    details: Built on PostgreSQL and DuckDB. Your GCS buckets, Spanner tables, and BigQuery data survive restarts.
  - icon: 🖥️
    title: Modern Console
    details: A beautiful, interactive dashboard to browse data, run SQL, and monitor service health.
  - icon: 🌉
    title: Hybrid Bridging
    details: Seamlessly route requests between local emulators and real GCP services for complex migrations.
---

<div class="quick-start">

## Start in 60 Seconds

```bash
docker run -d --name localcloud \
  -p 8080:8080 -p 4443:4443 -p 8085:8085 \
  -p 9010:9010 -p 9050:9050 -p 6379:6379 \
  -v localcloud-data:/var/lib/localcloud \
  localcloud/localcloud:latest
```

Access the console at [http://localhost:8080](http://localhost:8080)

</div>

<style>
:root {
  --vp-home-hero-name-color: transparent;
  --vp-home-hero-name-background: -webkit-linear-gradient(120deg, #4285F4 30%, #34A853);
}

.quick-start {
  max-width: 800px;
  margin: 4rem auto;
  padding: 0 1.5rem;
}

.quick-start h2 {
  text-align: center;
  border-bottom: none;
  font-size: 2rem;
  margin-bottom: 2rem;
}
</style>
