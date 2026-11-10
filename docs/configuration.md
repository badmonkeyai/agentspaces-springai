# Configuration

Written for: Spring AI developers who build with agentspaces-springai.

All properties sit under `agentspaces.springai`. The spaces that F2, F3, F4,
F6, and F8 use must be declared under `agentspaces.groups[].spaces`; a missing one
fails at startup with the YAML that adds it. F9 resolves the starter's vote and
aggregate capabilities (`agentspaces.capabilities.vote` and `.aggregate`, both
on by default) when a tool first runs; a tool whose capability is off answers
the model with an error.

```yaml
agentspaces:
  groups:
    - name: research-fleet
      founding: research-fleet-v1
      spaces:
        - { name: work }
        - { name: conversations }     # F3 memory
        - { name: model-requests }    # F4 model service
  springai:
    tools:
      prefix: fleet_
      exclude-agents: [internal-auditor]
    memory:
      enabled: true
      conversation-id: take
    capability-tools:
      vote: true                      # propose_vote, cast_ballot, read_tally, read_decision
      aggregate: false                # contribute, read_estimate
      pdt: false                      # pdt_read, pdt_assoc, pdt_history, pdt_pin, pdt_store_status (needs agentspaces.pdt.enabled)
    model-client:
      enabled: true                   # this node calls models through the fleet
    model-server:
      enabled: false                  # true on the peers that hold provider keys
      models: [gpt-4.1-mini]
    embedder:
      type: spring-ai
      model: text-embedding-3-small
    usage:
      enabled: true
    mcp:
      enabled: false
      include-agents: []
      capability-tools: false
```

| Property | Default | Meaning |
| --- | --- | --- |
| `group` | first group | The group this project works in |
| `tools.enabled` | `true` | Register `FleetTools` |
| `tools.prefix`, `tools.include-agents`, `tools.exclude-agents`, `tools.require-attested` | `fleet_`, all, none, `false` | Tool naming and filtering |
| `tools.timeout`, `tools.on-timeout` | `30s`, `result` | How long a tool call waits, and whether a timeout returns an explanation or throws |
| `tools.refresh-interval`, `tools.cache-ttl` | `5s`, `1s` | Change-event cadence and snapshot caching |
| `discovery-tools.agents`, `.assets`, `.data` | `false` | Enable each discovery tool |
| `discovery-tools.data-space`, `.timeout`, `.max-result-chars` | `data`, `10s`, `20000` | Data space, query timeout, result cap |
| `capability-tools.vote`, `.aggregate`, `.content` | `false` | Enable the vote tools (`propose_vote`, `cast_ballot`, `read_tally`, `read_decision`), the aggregate tools (`contribute`, `read_estimate`), and the content swarm tools (`publish_content`, `fetch_content`, `read_content`, `describe_content`, `list_content`; the starter's `agentspaces.content.enabled` must build the swarm) |
| `capability-tools.ballot-lease`, `.settle-timeout`, `.fetch-timeout` | `1h`, `10s`, `10m` | The lease of proposals and ballots the tools write; how long `read_estimate` waits for the estimate to settle; how long `fetch_content` waits for an artifact |
| `take-lease.enabled`, `take-lease.stream-renew-interval` | `true`, `30s` | The lease advisor |
| `memory.enabled`, `.space`, `.ttl`, `.conversation-id`, `.max-messages` | `false`, `conversations`, `24h`, `take`, `20` | Chat memory in the space |
| `model-client.enabled`, `.space`, `.model`, `.timeout`, `.stream.on-restart` | `false`, `model-requests`, server default, `120s`, `fail` | The calling side of F4 |
| `model-server.enabled`, `.space`, `.models`, `.routing`, `.prices.<model>` | `false`, `model-requests`, none, `model`, `1.0` | The serving side of F4 |
| `model-server.lease`, `.chunk-interval`, `.chunk-max-deltas`, `.chunk-lease`, `.response-lease`, `.concurrency`, `.model-beans.<model>` | `2m`, `100ms`, `32`, `2m`, `10m`, `4`, none | Server tuning; `model-beans` maps a model to a `ChatModel` bean when several providers are present |
| `embedder.type`, `.model`, `.require-match`, `.cache-size` | `hashing`, class name, `true`, `4096` | F5 |
| `usage.enabled`, `.metrics-space`, `.entry-lease`, `.publish-interval` | `false`, none, `24h`, `30s` | F6 |
| `mcp.enabled`, `.include-agents`, `.discovery-tools`, `.capability-tools` | `false`, none, `false`, `false` | F7; the last two also export the enabled F2 and F9 tools |
| `vector-store.enabled`, `.space`, `.asset`, `.description`, `.freshness` | `false`, `data`, `knowledge-base`, a generic description, `5m` | F8: serve the application's `VectorStore` bean as a fleet data asset |
