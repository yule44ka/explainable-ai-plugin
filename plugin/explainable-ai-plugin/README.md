# Explainable AI Plugin

An IntelliJ IDEA plugin for generating AI-assisted code explanations, mapping explanation fragments back to source code, and inserting high-detail explanation comments into the editor.

The plugin supports two AI providers:

- Junie CLI for code generation and explanations.
- OpenAI API for explanation generation through configurable chat models.

## Features

- Explain selected code directly from the editor context menu.
- Analyze selected code for potential issues.
- Generate multi-level explanations for selected code.
- Switch explanation detail level between low, medium, and high.
- Switch explanation format between paragraph and bullet points.
- Highlight mapped explanation fragments and navigate back to related code.
- Generate or modify code with Junie, then automatically detect changed project files and generate explanations for those changes.
- Insert high-detail bullet explanations as comments before mapped code blocks.
- Choose explanation provider in the tool window or settings.
- Show OpenAI model selection only when the OpenAI API provider is selected.

## UI Overview

The plugin adds an `AI assistant` tool window with two main tabs:

- `Explanation Generator`: generates explanations for selected code and lets you browse mapped explanation/code relationships.
- `Code Generation`: sends a natural-language prompt to Junie CLI, detects changed project files, and generates explanations for the resulting code changes.

The editor context menu also includes an `AI Assistant` group with:

- `Explain Code`
- `Analyze Code`
- `Generate Explanation`
- `Add Explanation Comments`

## Configuration

Open `Settings | Tools | Explainable AI`.

### Junie CLI

1. Install and configure the Junie CLI so the `junie` executable is available on `PATH`.
2. Add your Junie API key in the plugin settings.
3. Select `Junie` as the explanation provider.

When Junie is selected, model selection is hidden because the plugin uses fixed Junie models internally.

### OpenAI API

1. Add your OpenAI API key in the plugin settings.
2. Select `OpenAI API` as the explanation provider.
3. Configure the API endpoint, model, temperature, and max tokens if needed.

OpenAI credentials and Junie credentials are stored through the IntelliJ Platform password safe.

## Typical Workflows

### Generate an Explanation for Selected Code

1. Select code in the editor.
2. Open the `AI assistant` tool window.
3. Choose detail level and format.
4. Click `Generate Explanation`.
5. Review the generated explanation and mapped code highlights.

### Generate Code and Explain the Changes

1. Open the `Code Generation` tab.
2. Enter a natural-language prompt.
3. Click `Generate Code`.
4. The plugin runs Junie CLI, refreshes the project files, detects changed text files, and generates explanations for the changed segments.

### Insert Explanation Comments

1. Generate an explanation with mappings.
2. Click `Add Explanation Comments`.
3. The plugin inserts high-detail bullet explanations as comments before the mapped code chunks.

## Development

### Requirements

- JDK 21
- IntelliJ IDEA compatible with build `252.25557` or newer
- Gradle wrapper from this repository
- Junie CLI on `PATH` if you want to test Junie-backed workflows

### Build

```bash
./gradlew build
```

### Run in a Sandbox IDE

```bash
./gradlew runIde
```

### Project Structure

```text
src/main/kotlin/com/example/explainableaiplugin
├── actions/          Editor actions
├── api/              OpenAI HTTP client
├── services/         OpenAI, Junie, change detection, and comment insertion services
├── settings/         Persistent settings and configurable UI
└── MyToolWindow.kt   Main tool window UI
```

The plugin manifest is located at:

```text
src/main/resources/META-INF/plugin.xml
```
