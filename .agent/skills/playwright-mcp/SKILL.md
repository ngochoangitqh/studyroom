---
name: playwright-mcp
description: >-
  Automate browser interactions, navigate web pages, take DOM accessibility snapshots, click elements,
  fill forms, run end-to-end web tests, and inspect web applications using the Microsoft Playwright MCP server.
---

# Microsoft Playwright MCP Server Guide

This skill provides operational guidance for controlling web browsers via the official **Microsoft Playwright MCP server** (`@playwright/mcp`).

## Core Concept & Architecture

Unlike visual/screenshot-only agents, the Playwright MCP server exposes structured **accessibility tree snapshots** of the browser's DOM:
- **Fast & Deterministic**: High accuracy without needing computer-vision OCR or coordinate guessing.
- **Direct Automation**: Native Playwright commands for clicking, typing, navigating, hovering, and taking screenshots.
- **Headless & Headed**: Runs in headless mode by default or headed mode when visual debugging is required.

## Configuration

The Playwright MCP server is configured in `~/.gemini/config/mcp_config.json`:

```json
{
  "mcpServers": {
    "playwright": {
      "command": "cmd.exe",
      "args": [
        "/c",
        "npx",
        "-y",
        "@playwright/mcp@latest"
      ]
    }
  }
}
```

### Common Flags & Customization
- `--headless`: Run without a visible UI window (ideal for CI/CD or background tasks).
- `--browser=<chromium|firefox|webkit|msedge>`: Select browser engine (Chromium is installed by default).
- `--device="iPhone 15"`: Emulate specific mobile devices and screen resolutions.
- `--viewport-size="1280x720"`: Set custom viewport resolution.
- `--storage-state=path/to/auth.json`: Reuse saved cookies / logged-in sessions.

## Typical Workflow

1. **Navigate**: Open the target URL.
2. **Snapshot**: Capture accessibility tree to discover elements, roles, and accessible names.
3. **Interact**:
   - Click buttons / links.
   - Fill inputs or forms.
   - Select dropdowns or check boxes.
4. **Assert / Verify**:
   - Check page text, URL change, or DOM states.
   - Take screenshots if visual confirmation is needed.
