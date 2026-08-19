# AgentLens demo image: multi-stage build, no Maven at runtime.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
COPY agentlens-core/pom.xml agentlens-core/
COPY agentlens-adapters/pom.xml agentlens-adapters/
COPY agentlens-eval/pom.xml agentlens-eval/
COPY agentlens-cli/pom.xml agentlens-cli/
# Dependency layer cached separately from sources
RUN mvn -q -B dependency:go-offline -pl agentlens-cli -am || true
COPY agentlens-core/src agentlens-core/src
COPY agentlens-adapters/src agentlens-adapters/src
COPY agentlens-eval/src agentlens-eval/src
COPY agentlens-cli/src agentlens-cli/src
RUN mvn -q -B clean install -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/agentlens-cli/target/agentlens-cli-*.jar /app/cli.jar
COPY --from=build /src/agentlens-core/target/agentlens-core-*.jar /app/lib/core.jar
COPY --from=build /src/agentlens-adapters/target/agentlens-adapters-*.jar /app/lib/adapters.jar
COPY --from=build /src/agentlens-eval/target/agentlens-eval-*.jar /app/lib/eval.jar
COPY --from=build /root/.m2/repository /root/.m2/repository
COPY data/scenarios/ops-scenarios.json /app/data/scenarios/ops-scenarios.json
COPY demo/demo.sh /app/demo.sh
RUN chmod +x /app/demo.sh && mkdir -p /app/output
ENTRYPOINT ["/app/demo.sh"]
