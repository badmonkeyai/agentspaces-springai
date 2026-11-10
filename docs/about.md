# About agentspaces-springai

Written for: Spring AI developers who build with agentspaces-springai.

**A peer-to-peer coordination fabric for agents, services, and the people who work with them.**

AgentSpaces is a P2P framework to quickly discover and join P2P networks, where agents, software services, and people share assets, queries, results, and coordinate. The framework helps you build fleets of agents, tie in other software, and coordinate across them with a replicated tuple space, quorum building, and more. Agents post work, claim tasks, publish results, vote on decisions, and recover from each other's failures. There is no broker, no registry, no scheduler, and no server to keep alive. Coordination is peer-to-peer, secured end to end, and replicated across the fleet.

## About agentspaces-springai

AgentSpaces for Spring AI applications. Spring AI gives an application a
portable, well-engineered way to call models; AgentSpaces gives a fleet of
processes a shared, leased, signed, replicated tuple space. This project plugs
the fleet into Spring AI's own extension points (tools, advisors, chat memory,
`ChatModel`, `EmbeddingModel`, observations, and MCP), so an application stays
ordinary Spring AI code while its agents coordinate across machines.

It needs no Embabel. The Embabel extension (`embabel-agentspaces` in the core
repository) integrates at the level of a planned agent and its actions; this
project integrates at the level of a model call and its tools. An application
can use either or both.

Requirements: Java 21, Spring Boot 4.1, Spring AI 2.0, and the AgentSpaces
starter (`agentspaces-spring-boot-starter`). The design and its rationale are in
[`docs/design.md`](../docs/design.md).
