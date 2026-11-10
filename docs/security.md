# Security

Written for: Spring AI developers who build with agentspaces-springai.

- **Prompts cross the fleet under F3 and F4.** Give the conversation and model
  spaces their own admission (`allowlist`, `credential`, or `authorizer`) and a
  group content key.
- **Only trusted servers answer.** `FleetChatModel` accepts a response or stream
  chunk only from an issuer the authorizer permits `MODEL_SERVE` for the model:
  `agentspaces.security.grants.model-serve` under the membership profiles, the
  `aspace:model-serve[:<model>]` scope under the OIDC profiles.
- **Tool descriptions are model input.** Filter which cards become tools, and
  consider `tools.require-attested=true` with subordinate agent keys.
- **A capability tool acts as this peer's agent.** A ballot `cast_ballot` writes
  counts as the application's own vote, so enable F9 only on peers whose model
  should hold that vote.
- **Credentials stay on server peers.** Under F4, provider keys live only on the
  `ModelServer` peers.
- **MCP clients are outside the fleet.** F7 exposes nothing until named.
