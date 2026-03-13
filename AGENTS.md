# Kestra GraalVM Plugin

## What

Use GraalVM scripting engine with Kestra data workflows. Exposes 6 plugin components (tasks, triggers, and/or conditions).

## Why

Enables Kestra workflows to interact with GraalVM, allowing orchestration of GraalVM-based operations as part of data pipelines and automation workflows.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `graalvm`

### Key Plugin Classes

- `io.kestra.plugin.graalvm.js.Eval`
- `io.kestra.plugin.graalvm.js.FileTransform`
- `io.kestra.plugin.graalvm.python.Eval`
- `io.kestra.plugin.graalvm.python.FileTransform`
- `io.kestra.plugin.graalvm.ruby.Eval`
- `io.kestra.plugin.graalvm.ruby.FileTransform`

### Project Structure

```
plugin-graalvm/
├── src/main/java/io/kestra/plugin/graalvm/ruby/
├── src/test/java/io/kestra/plugin/graalvm/ruby/
├── build.gradle
└── README.md
```

### Important Commands

```bash
# Build the plugin
./gradlew shadowJar

# Run tests
./gradlew test

# Build without tests
./gradlew shadowJar -x test
```

### Configuration

All tasks and triggers accept standard Kestra plugin properties. Credentials should use
`{{ secret('SECRET_NAME') }}` — never hardcode real values.

## Agents

**IMPORTANT:** This is a Kestra plugin repository (prefixed by `plugin-`, `storage-`, or `secret-`). You **MUST** delegate all coding tasks to the `kestra-plugin-developer` agent. Do NOT implement code changes directly — always use this agent.
