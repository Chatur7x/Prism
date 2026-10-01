# =============================================================================
# PRISM backend image
# =============================================================================
# Multi-stage so the runtime layer contains no Maven, no source, and no build
# cache. A Spring Boot fat jar is self-contained apart from the JRE, so the
# second stage needs nothing but a JRE and the jar.
# =============================================================================

# ---- build -----------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

# Dependencies are resolved in their own layer, keyed on the POM alone. Editing
# a .java file then rebuilds in seconds instead of re-downloading the world.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# The package is built without tests here because the image is not the place to
# run them: `docker compose build` must not silently pass a broken build, and the
# test suite needs no infrastructure beyond this same layer. CI runs `mvn test`
# as its own gate before it ever builds an image.
RUN mvn -B -q clean package -DskipTests

# ---- runtime ---------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy AS runtime

# curl is the only addition: the compose healthcheck uses it, and an image
# without it would have no way to report its own liveness to an orchestrator.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# An unprivileged user with no shell and no home. The application writes nothing
# to the filesystem, so it needs to own nothing.
RUN groupadd --system --gid 1001 prism \
    && useradd --system --uid 1001 --gid prism --no-create-home --shell /usr/sbin/nologin prism

WORKDIR /app

# Ownership is set on the copy rather than with a later chown, so the jar is
# never world-readable in an intermediate layer.
COPY --from=build --chown=prism:prism /build/target/prism-backend-*.jar /app/prism.jar

USER prism

EXPOSE 8080

# The JVM is told the container's real memory limit. Without this the JVM sizes
# its heap from the *host's* total memory and the kernel OOM-kills the process
# under a container limit, which looks exactly like a random crash.
#
# MaxRAMPercentage rather than a fixed -Xmx so the same image works at any
# memory limit the operator sets. The garbage collector is deliberately left at
# the JDK 17 default (G1): the pipeline runs several documents concurrently, and
# forcing a throughput-free collector here would be a real regression dressed up
# as a saving. The remaining flags cover the two things that actually go wrong
# in a container: headless AWT (PDFBox text extraction) and a file.encoding that
# differs from the host's, which would otherwise corrupt document text.
ENV LANG=C.UTF-8

# healthcheck duplicates the compose healthcheck so a plain `docker run` is
# still self-verifying. `start-period` is generous because Flyway runs before the
# context is ready and a first-run schema migration is not instant.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

# Flags are on the command line rather than in JAVA_TOOL_OPTIONS: the JVM prints
# a "Picked up JAVA_TOOL_OPTIONS" line to stderr on every launch otherwise, and
# that noise lands in the application log ahead of every single startup.
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75", \
    "-Djava.awt.headless=true", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dfile.encoding=UTF-8", \
    "-jar", "/app/prism.jar"]
