# Security Policy

AgentSpaces Spring AI exposes an AgentSpaces fleet to Spring AI applications:
fleet capabilities become LLM tools, fleet peers serve models, and agents run
LLM work under the fleet's leases. It inherits the security model of the
AgentSpaces core (signed, self-certifying peers and agents; the threat model in
the AgentSpaces specification, SPEC §11) and adds the risks of putting a model
in the loop.

## Reporting a vulnerability

Please report vulnerabilities **privately** via GitHub's security advisories:

> https://github.com/badmonkeyai/agentspaces-springai/security/advisories/new

A vulnerability in the AgentSpaces core libraries belongs in the core
repository's advisories instead:
https://github.com/badmonkeyai/agentspaces/security/advisories/new

Do not open public issues for suspected vulnerabilities, and do not include
exploit details in public discussions until a fix is released.

We will acknowledge your report within five business days, keep you informed of
progress, credit you in the advisory unless you prefer otherwise, and
coordinate the disclosure timeline with you. There is currently no bug bounty
program.

Questions can be sent to `oss [at] badmonkey.ai`

## Scope

In scope: anything that lets an attacker violate a guarantee this project or
the core claims through the Spring AI integration. For example:
- a foreign AgentCard that makes a tool callback invoke something it should not;
- tool output or model output that escapes the fleet's admission and authorization checks;
- usage records attributed to the wrong agent;
- an MCP export that exposes more of the fleet than configured.

Prompt injection that stays within what a tool is allowed to do is a property
of the model, not a vulnerability here. A report showing that a tool's
boundary does not hold is very welcome.

## Supported versions

During 0.x, only the latest minor release receives security fixes.
