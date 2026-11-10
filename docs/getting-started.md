# Getting started

Written for: Spring AI developers who build with agentspaces-springai.

The library is on Maven Central under the group `ai.badmonkey.agentspaces`,
version `0.6.0`; no repository configuration is needed. Add it beside the
AgentSpaces starter and a Spring AI model starter. The library releases from
this repository on its own cadence, so it carries its version explicitly; the
core's `agentspaces-dependencies` BOM manages the starter's.

Maven (`pom.xml`):

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>ai.badmonkey.agentspaces</groupId>
      <artifactId>agentspaces-dependencies</artifactId>
      <version>0.6.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>ai.badmonkey.agentspaces</groupId>
    <artifactId>agentspaces-springai</artifactId>
    <version>0.6.0</version>
  </dependency>
  <dependency>
    <groupId>ai.badmonkey.agentspaces</groupId>
    <artifactId>agentspaces-spring-boot-starter</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
  </dependency>
</dependencies>
```

Gradle Kotlin DSL (`build.gradle.kts`):

```kotlin
dependencies {
    implementation(platform("ai.badmonkey.agentspaces:agentspaces-dependencies:0.6.0"))
    implementation("ai.badmonkey.agentspaces:agentspaces-springai:0.6.0")
    implementation("ai.badmonkey.agentspaces:agentspaces-spring-boot-starter")
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
}
```

Gradle Groovy DSL (`build.gradle`):

```groovy
dependencies {
    implementation platform('ai.badmonkey.agentspaces:agentspaces-dependencies:0.6.0')
    implementation 'ai.badmonkey.agentspaces:agentspaces-springai:0.6.0'
    implementation 'ai.badmonkey.agentspaces:agentspaces-spring-boot-starter'
    implementation 'org.springframework.ai:spring-ai-starter-model-openai'
}
```

The Spring AI model starter is whichever provider the application uses; its
version comes from the `spring-ai-bom` or the Spring Boot parent, as in any
Spring AI project.

A worker is a `@SpaceAgent` bean with an injected `ChatClient.Builder` and a
`@SpaceTake` method. The space distributes the work, the model does it, and the
take lease renews itself on every model round-trip:

```java
@SpaceAgent(description = "Summarizes a topic into a short briefing")
public class Summarizer {
    private final ChatClient chat;

    Summarizer(ChatClient.Builder builder) { this.chat = builder.build(); }

    @SpaceTake(space = "work", lease = "2m")
    public Summary summarize(Brief brief) {
        return new Summary(brief.topic(), chat.prompt().user("Summarize: " + brief.topic()).call().content());
    }
}
```

An orchestrator attaches the fleet's tools to its `ChatClient`, and its model
calls agents on other peers from its own tool loop:

```java
@Bean
Orchestrator orchestrator(ChatClient.Builder builder, FleetTools fleet) {
    return new Orchestrator(builder.defaultToolCallbacks(fleet.toolCallbacks()).build());
}
```

[Example 15](../examples/springai-fleet/README.md) runs exactly this across three
Spring Boot applications.
