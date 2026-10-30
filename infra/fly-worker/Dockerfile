# Worker contínuo na Fly (docs/FLY_WORKER.md). Build context: raiz do repositório.
# Mesmas bases fixadas por digest de Dockerfile.vercel/infra/Dockerfile.api; nunca executa migration nem abre porta.
FROM maven:3.9.11-eclipse-temurin-25@sha256:407c4423cec0cf2981055bc2c6c0dc211d9605b6669279b95997f2d1c7e91e2c AS build
WORKDIR /work
COPY apps/backend apps/backend
COPY db/migrations db/migrations
COPY infra/supabase/bootstrap/foundation-manifest.json infra/supabase/bootstrap/foundation-manifest.json
RUN mvn -B -ntp -f apps/backend/pom.xml -DskipTests package

FROM eclipse-temurin:25-jre-noble@sha256:ce8543ddd3070ccbeaac2106a74c9387ddeb8d47a1c43348345715c1ef245c7f
WORKDIR /app
COPY --from=build --chown=10001:10001 /work/apps/backend/target/condpulse-backend-0.1.0-SNAPSHOT.jar /app/app.jar
COPY --chown=10001:10001 infra/fly-worker/start-worker.sh /app/start-worker.sh
USER 10001:10001
# Perfil worker sem servidor HTTP: nenhuma porta exposta.
ENV SPRING_PROFILES_ACTIVE=worker
ENTRYPOINT ["/bin/sh", "/app/start-worker.sh"]
