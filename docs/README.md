# agentspaces-springai documents

Written for: anyone who read the README and wants the rest of the story.

The README is short by design: what this is, the quick start, and how to add it to a build. Everything else lives here, one topic per document.

| Document | Written for | What it holds |
|---|---|---|
| [About agentspaces-springai](about.md) | Spring AI developers who build with agentspaces-springai | What AgentSpaces is, and what this project adds to it. |
| [How to use AI with AgentSpaces](how-to-use-ai.md) | Spring AI developers who build with agentspaces-springai | Tested walk-throughs that build an agent, hand it work from another peer, and survive a crash. |
| [Getting started](getting-started.md) | Spring AI developers who build with agentspaces-springai | The library is on Maven Central under the group `ai.badmonkey.agentspaces`, version `0.6.0`; no repository configuration is needed. |
| [Features](features.md) | Spring AI developers who build with agentspaces-springai | Each feature has its own auto-configuration and its own `enabled` property; F1 and the take-lease advisor are on by default. |
| [Extension points](extension-points.md) | Spring AI developers who build with agentspaces-springai | Every decision a deployment might want to change is an interface with one default bean. |
| [Configuration](configuration.md) | Spring AI developers who build with agentspaces-springai | All properties sit under `agentspaces.springai`. |
| [Security](security.md) | Spring AI developers who build with agentspaces-springai | Security |
| [Building and testing](building-and-testing.md) | contributors who build and test this repository | The project consumes the published AgentSpaces libraries by Maven coordinates (`agentspaces.version` in the parent pom), resolved from Maven Central: |
| [Contributing](contributing.md) | contributors | How to contribute: the Developer Certificate of Origin, the code of conduct, and private vulnerability reports. |
| [The design memo](design.md) | maintainers who want the design and its rationale | The approved design, how the build departed from it, and what remains. |
