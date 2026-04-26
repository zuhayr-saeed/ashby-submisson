# ---------- build stage ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY src/Main.java /app/src/Main.java
RUN javac -encoding UTF-8 -d /app/out /app/src/Main.java

# ---------- runtime stage ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# Compiled classes and the labeled training data the model trains on at boot.
COPY --from=build /app/out /app/out
COPY data /app/data

ENV PORT=8000 \
    HOST=0.0.0.0 \
    JAVA_OPTS="-Xms256m -Xmx512m"

EXPOSE 8000

# The hosting platform should poll GET /health (returns 200 when the model is loaded).
# We do not declare a Docker HEALTHCHECK here because the slim JRE image lacks curl/wget;
# the GET /health endpoint is the canonical liveness/readiness check.

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -cp /app/out Main"]
