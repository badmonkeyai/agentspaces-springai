# agentspaces-springai changelog

What changed in the Spring AI integration, newest first. The Java core's history is in `docs/dvit/CHANGELOG.md` of the AgentSpaces workspace.

## 0.7.0 (released 2026-11-27)

Released on 2026-11-27. Work since the 0.6.0 release; the build reads 0.7.0.

- **Spring AI 2.1.0-RC2.** The project builds on Spring AI 2.1.0-RC2, with Spring Boot 4.1.1 and Embabel 1.5.2 unchanged. 2.1.0 GA follows later in 0.7.x. No `agentspaces.springai.*` key changes.
- **Tools.** Spring AI 2.1 deprecates `defaultToolCallbacks(...)` and `toolCallbacks(...)` for removal. Register the fleet's tools with `defaultTools(fleet.toolCallbacks())` or `tools(...)`. Spring AI's own `spring.ai.tools.limits.*` keys cap tool calls (40 per tool and 150 in total by default). `docs/configuration.md`, "Spring AI 2.1 notes", has the detail.
- **Anthropic sampling.** The model server still forwards `temperature`, `topP` and `topK`; Spring AI 2.1's Anthropic model drops them and logs a warning.
- **Streams and memory.** The model server skips a stream delta that carries nothing. `MemoryAdvisor.CONVERSATION_ID` replaces `ChatMemory.CONVERSATION_ID`.
- **Periodic work on the node's clock.** Tool refresh, usage publishing, `TakeLeaseAdvisor` and the model server repeat with a fixed delay instead of a fixed rate. The application's `InstantSource` bean drives usage and the tool cache. The vector store connector honours `agentspaces.watchdog.connector-hold-limit`, and `VectorStoreConnector.runtime()` is new.
