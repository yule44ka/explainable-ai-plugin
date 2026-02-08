# Explainable AI Plugin for IntelliJ IDEA

An IntelliJ IDEA plugin for explainable artificial intelligence with integrated code generation capabilities.

## Features

### 1. Code Summary Generator
- Generate multi-level summaries (low, medium, high detail) of selected code
- Interactive mapping between summary components and code segments
- Support for both paragraph and bullet-point formats

### 2. Junie Code Generation
- Generate code directly from natural language prompts using Junie CLI
- Seamless integration with your IntelliJ IDE workspace
- Automatic code modifications through Junie CLI

## Requirements

- JDK 21+
- Gradle 9.0+ (wrapper included)
- Junie CLI installed and available in PATH (for code generation feature)

## Quick Start

Navigate to the plugin directory and build:

```bash
cd plugin/explainable-ai-plugin
./gradlew build
```

Run the plugin in development mode:

```bash
./gradlew runIde
```

or click "Run IDE with Plugin" in IntelliJ IDEA

## Configuration

Before using the plugin, configure the required credentials in **Settings → Tools → Explainable AI**:

1. **OpenAI API Key** - Required for code summary generation (get it from https://platform.openai.com/api-keys)
2. **Junie API Key** - Required for code generation (get it from https://junie.jetbrains.com/cli)

## Using the Code Generation Feature

1. Open the Explainable AI tool window (View → Tool Windows → Explainable AI)
2. In the "Junie Code Generation" section, enter your prompt in the text field
3. Click "✨ Generate Code"
4. Junie CLI will process your prompt and modify the code in your project accordingly

The code generation feature uses Junie CLI under the hood, which means:
- Changes are applied directly to your project files
- You can use natural language to describe what code you want to generate
- Junie handles the entire code modification process
