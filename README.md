# agentspaces-springai

**AgentSpaces for Spring AI applications**, from Bad Monkey AI. It plugs a peer-to-peer fleet into Spring AI's own extension points, so a Spring AI developer's agents take work from a shared space, call models through the fleet, and coordinate across machines while the code stays ordinary Spring AI.

## Quick start: build your first agent with Spring AI

An agent is a method. Its parameter is the work it takes, and its return value is the next entry in the space. With Spring AI the method sits on a bean and calls a model: the space hands it one `Order` at a time under a lease, your `ChatClient` writes the packing note, and the returned `Shipment` completes the order.

**1. Add the dependencies.** The library, the AgentSpaces starter, and a Spring AI model starter. The Maven BOM and the Gradle forms are under [Getting started](docs/getting-started.md#getting-started).

```xml
<dependency>
  <groupId>ai.badmonkey.agentspaces</groupId>
  <artifactId>agentspaces-springai</artifactId>
  <version>0.6.0</version>
</dependency>
<dependency>
  <groupId>ai.badmonkey.agentspaces</groupId>
  <artifactId>agentspaces-spring-boot-starter</artifactId>
  <version>0.6.0</version>
</dependency>
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

**2. Configure the peer.** One YAML file builds it. The default `mtls` security profile secures every link, so the file has no security block. The `${shop.*}` placeholders let you start a second instance from the command line.

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
  main:
    web-application-type: none   # the fleet is the interface
    keep-alive: true             # nothing else holds the JVM open

agentspaces:
  bind: 127.0.0.1:${shop.port:7701}
  groups:
    - name: shop
      founding: shop-v1
      seeds: ${shop.seeds:}      # empty on the first instance
      spaces:
        - name: orders
```

**3. Write the agent.** Two records name the entries, `@SpaceAgent` turns the bean into an agent, and `@SpaceTake` says what it takes and how long it may hold it. The `ApplicationRunner` only feeds the fleet an order.

```java
public record Order(String orderId, String item) {
}

public record Shipment(String orderId, String item, String note) {
}
```

```java
@SpaceAgent(name = "fulfiller", description = "Ships orders and writes a packing note")
public class Fulfiller {
    private final ChatClient chat;

    Fulfiller(ChatClient.Builder builder) {
        this.chat = builder.build();
    }

    @SpaceTake(lease = "2m")
    public Shipment ship(Order order) {
        String note = chat.prompt().user("Write a one-line packing note for: " + order.item()).call().content();
        return new Shipment(order.orderId(), order.item(), note);
    }
}
```

```java
@SpringBootApplication
public class ShopApp {

    public static void main(String[] args) {
        SpringApplication.run(ShopApp.class, args);
    }

    @Bean
    ApplicationRunner desk(AgentSpaces spaces, @Value("${shop.order:}") String item) {
        return args -> {
            if (!item.isBlank()) {
                Space orders = spaces.group("shop").space("orders");
                orders.write(new Order("o-1", item), Lease.of(Duration.ofMinutes(10)));
                orders.read(Template.of(Shipment.class), Duration.ofSeconds(30)).ifPresent(System.out::println);
            }
        };
    }
}
```

**4. Run two instances.** Set `OPENAI_API_KEY` (any Spring AI model starter works), then start the first instance, and a second one that joins through it and writes an order. The listings omit imports.

```
mvn -q package
java -jar target/<your-app>.jar
java -jar target/<your-app>.jar --shop.port=7702 --shop.seeds=127.0.0.1:7701 --shop.order=kite
```

The second instance prints the shipment with its packing note. Either instance can take the order, because both run the same agent.

**5. Kill one.** Add `System.out.println("shipping " + order.orderId());` and `Thread.sleep(10_000);` at the top of `ship` (the method then needs `throws InterruptedException`), and shorten the lease to `@SpaceTake(lease = "30s")`. Start both instances, write an order from the second, and run `kill -9` on whichever instance prints `shipping`. If you killed the first instance, the second prints the shipment. If you killed the second, the first prints `shipping o-1` again when the lease lapses. The `TakeLeaseAdvisor` renews the lease on every model round trip while an instance lives, so only a dead instance loses its order.

The [documents](docs/README.md) add the rest: fleet tools for an orchestrator's model, crash-safe model loops with chat memory, model access as a fleet service, and MCP export.

## What it is

- A Spring Boot library beside the AgentSpaces starter: fleet tools, advisors, chat memory, and a `ChatModel` whose provider is the fleet.
- Agents are annotated methods on beans; the space hands each one its work under a lease, and a crashed peer's work comes back.
- Model access as a fleet service: one peer holds the key, every other peer calls `ChatClient`.
- The fleet's agents and capabilities as Spring AI tools and over MCP.
- Java 21, Spring Boot 4.1, Spring AI 2.0.

## Add it to your build

The library sits beside the AgentSpaces starter and a Spring AI model starter, all on Maven Central:

```xml
<dependency>
  <groupId>ai.badmonkey.agentspaces</groupId>
  <artifactId>agentspaces-springai</artifactId>
  <version>0.6.0</version>
</dependency>
```

[Getting started](docs/getting-started.md) has the BOM and the Gradle forms, and [Configuration](docs/configuration.md) lists every property.

## Where to read next

1. **[The developer guide](https://www.badmonkey.ai/agentspaces/developer-guide.html#spring-ai)**: its Spring AI chapter covers workers, fleet tools, model access, patterns, and deployment recipes.
2. **[The examples](examples/)**: `springai-fleet` runs a fleet of Spring AI workers, and `springai-patterns` shows the patterns one by one.
3. **[The documents](docs/README.md)**: everything this README leaves out, one topic per document, each with the reader it is written for.

## License

Apache License 2.0, Copyright 2026 Bad Monkey, Inc. See [LICENSE](LICENSE) and [NOTICE](NOTICE). Contributions are welcome under the Developer Certificate of Origin; see [CONTRIBUTING.md](CONTRIBUTING.md). Report a vulnerability privately, as [SECURITY.md](SECURITY.md) describes.
