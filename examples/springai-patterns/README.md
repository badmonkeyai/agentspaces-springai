# Spring AI patterns on the fleet

Three patterns that combine the AgentSpaces annotations with Spring AI, with
no new API in this project. Each runs on its own small fleet over TCP, on scripted
models so it runs offline; every agent takes a `ChatClient` (or a Spring AI
`Evaluator` built on one), so a real provider drops in with no other change.

The patterns assemble their peers with the core API (`PatternPeer`), as the
numbered core examples do, which keeps each multi-peer pattern in one file.
Under Spring Boot each agent is a `@SpaceAgent` bean with an injected
`ChatClient.Builder`, as [example 15](../springai-fleet/README.md) shows.

## Judges: a panel of models votes

[JudgePanel.java](src/main/java/ai/badmonkey/agentspaces/examples/springai/patterns/JudgePanel.java).
A `Claim` written to the votes space is the cue: the lead's `@SpaceNotify`
method returns a `Motion`, and the binder opens the vote as the lead, once per
claim id (ISSUE-Motion, 0.3.0). Each judge is a `@Ballot` agent whose vote comes
from Spring AI's `FactCheckingEvaluator` over the judge's own model, checking
the claim against its evidence. The lead's `@OnDecision` method records the
verdict when the quorum closes. Every ballot is a signed entry, so which judge
voted how is auditable from the space.

```java
@SpaceNotify(space = "votes", lease = "1h")
public Motion open(Claim claim) {
    return Motion.in("votes", "claim:" + claim.id(), question(claim.claim(), claim.evidence()),
            List.of("approve", "reject"), quorum, Lease.of(Duration.ofMinutes(10)));
}

@Ballot(space = "votes", prefix = "claim:", lease = "10m")
public String judge(VoteCapability.Proposal proposal) {
    String[] parts = proposal.question().split(EVIDENCE, 2);
    boolean holds = evaluator.evaluate(new EvaluationRequest(
            List.of(new Document(parts[1])), parts[0])).isPass();
    return holds ? "approve" : "reject";
}
```

With a strict, a careful, and a lenient judge, a true claim passes 3 to 0 and a
false one fails 2 to 1: the lenient judge is outvoted.

## Priced by tokens: the cheapest capable model wins

[TokenPricedAuction.java](src/main/java/ai/badmonkey/agentspaces/examples/springai/patterns/TokenPricedAuction.java).
The tasks space runs AUCTION. Each model worker's `@BidFunction` prices a task
from its model's price per thousand tokens and an estimate of the prompt's size,
and bids infinity for tasks harder than its model handles well. Routine tasks go
to the cheap mini model and hard ones to the premium model, and adding a
provider means starting one more peer.

```java
@BidFunction(space = "model-tasks")
public double bid(ModelTask task) {
    if (task.difficulty() > maxDifficulty) {
        return Double.POSITIVE_INFINITY;
    }
    return pricePerThousandTokens * estimatedTokens(task.prompt()) / 1000.0;
}
```

## Extraction ensemble: models read, a vote files

[ExtractionEnsemble.java](src/main/java/ai/badmonkey/agentspaces/examples/springai/patterns/ExtractionEnsemble.java).
Extractors on different models react to every scanned document with
`@SpaceNotify` and write a candidate with their confidence; voters back the most
confident candidate with `@Ballot`; and the filer's `@OnDecision` files the
winner with the tally as provenance. This is core example 08's pipeline with
models in place of the hand-written readers.

## Running them

From `agentspaces-springai/`, after `mvn -q install -DskipTests` (examples build
only under `-Pexamples`):

```
mvn -q -Pexamples -pl examples/springai-patterns exec:java
mvn -q -Pexamples -pl examples/springai-patterns test
```

[PatternsTest](src/test/java/ai/badmonkey/agentspaces/examples/springai/patterns/PatternsTest.java)
asserts each pattern's outcome over TCP.

## Next steps

- **A vector store as shared knowledge.** `VectorStoreAssetProvider` in the
  library serves a Spring AI `VectorStore` as a fleet data asset; agents query it
  through `DataQueryClient` or the `fetch_fleet_data` tool, and identical
  questions within the freshness window are answered from the space.
- **Real models.** Replace `ScriptedModels.answering(...)` with the application's
  `ChatClient.Builder`; for judges, `FactCheckingEvaluator.builder(builder).build()`.
