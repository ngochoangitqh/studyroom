---
name: omniroute
description: >-
  Use OmniRoute as a unified local AI gateway and proxy server (running at http://localhost:20128/v1).
  Enables access to 350+ AI providers (150+ free tiers) with quota-aware auto-fallback,
  RTK+Caveman token compression, and multi-model routing (auto, auto/coding, etc.).
---

# OmniRoute AI Gateway

OmniRoute is an open-source AI gateway providing a single unified OpenAI-compatible endpoint for 350+ AI providers with automatic fallback, token compression, and quota management.

## Key Capabilities

1. **Unified Endpoint**: Exposes standard OpenAI-compatible completions and chat endpoints at `http://localhost:20128/v1`.
2. **Quota-Aware Fallback**: If a provider or key hits rate limits or quota, requests automatically fallback to the next available provider.
3. **Stacked Compression**: RTK + Caveman compression reduces token usage by 15–95% on eligible payloads.
4. **Virtual Combos**:
   - `auto`: Balanced default with Last-Known-Good-Provider (LKGP) stickiness.
   - `auto/coding`: Prioritizes code generation quality.
   - `auto/fast`: Lowest latency routing.
   - `auto/cheap`: Lowest cost per token routing.
   - `auto/offline`: Maximizes remaining quota and headroom.

## Quick Start & Service Control

### Starting the Server
To start the local gateway server:
```powershell
omniroute
```
Or run as a daemon / background service:
```powershell
omniroute start
```

The Web UI and dashboard will be available at:
- **Dashboard**: `http://localhost:20128`
- **API Base URL**: `http://localhost:20128/v1`
- **Free-tier Quota Tracking**: `http://localhost:20128/dashboard/free-tiers`

### Testing Connection
```powershell
curl http://localhost:20128/v1/chat/completions `
  -H "Content-Type: application/json" `
  -d '{"model":"auto","messages":[{"role":"user","content":"Hello!"}]}'
```

### Stopping the Server
```powershell
omniroute stop
```
