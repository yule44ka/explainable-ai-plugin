# Explainable AI Plugin for IntelliJ IDEA

An IntelliJ IDEA plugin for explainable artificial intelligence.

## Requirements

- JDK 21+
- Gradle 9.0+ (wrapper included)

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

This will launch IntelliJ IDEA Community Edition 2025.2.4 with the plugin installed.

## Installation

1. Build the plugin: `./gradlew buildPlugin`
2. Open IntelliJ IDEA → **Settings** → **Plugins**
3. Click ⚙️ → **Install Plugin from Disk...**
4. Select `build/distributions/explainable-ai-plugin-1.0-SNAPSHOT.zip`
5. Restart IDE

## Configuration

- **IntelliJ IDEA**: 2025.2.4 Community Edition
- **Kotlin**: 2.1.20
- **JVM Target**: 21

## License

MIT
