FROM eclipse-temurin:21.0.12_8-jre-ubi9-minimal@sha256:fa6a3cd1e88402446002f86e0d06f5202d7d6ec02d525888f9c0ea2eeb5b07ad
ARG MODULE
ARG SOURCE_FINGERPRINT
LABEL org.opencontainers.image.source="https://github.com/lirji/agentic-ai-platform" \
      io.agentic.source-fingerprint="${SOURCE_FINGERPRINT}"
WORKDIR /app
COPY jars/${MODULE}.jar /app/service.jar
COPY jars/knowledge-parser.jar /app/knowledge-parser.jar
USER 10001:10001
ENTRYPOINT ["java", "-Xms64m", "-Xmx384m", "-XX:ActiveProcessorCount=2", "-jar", "/app/service.jar"]
