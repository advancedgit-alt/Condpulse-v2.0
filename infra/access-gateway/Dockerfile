# Gateway de acesso do condomínio (docs/integracoes/facial/GATEWAY.md). Build context: raiz do repositório.
# Mesmas bases fixadas por digest de infra/fly-worker/Dockerfile. Nenhuma publicação automática: rodar no condomínio
# (modo LOCAL) ou como serviço central (modo NUVEM) exige decisão e OK do responsável.
FROM maven:3.9.11-eclipse-temurin-25@sha256:407c4423cec0cf2981055bc2c6c0dc211d9605b6669279b95997f2d1c7e91e2c AS build
WORKDIR /work
COPY apps/access-gateway apps/access-gateway
RUN mvn -B -ntp -f apps/access-gateway/pom.xml -DskipTests package

FROM eclipse-temurin:25-jre-noble@sha256:ce8543ddd3070ccbeaac2106a74c9387ddeb8d47a1c43348345715c1ef245c7f
WORKDIR /app
COPY --from=build --chown=10001:10001 /work/apps/access-gateway/target/condpulse-access-gateway-0.1.0-SNAPSHOT.jar /app/app.jar
USER 10001:10001
# Começa desligado: só sobe com CP_GATEWAY_ENABLED=true, configuração, segredos montados e /data persistente.
ENV CP_GATEWAY_DATA_DIR=/data
VOLUME ["/data"]
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-Djdk.httpclient.disableRetryConnect=true","-jar","/app/app.jar"]
