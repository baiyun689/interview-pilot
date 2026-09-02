FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace

COPY gradlew gradlew.bat build.gradle settings.gradle ./
COPY gradle ./gradle
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon dependencies

COPY src ./src
RUN ./gradlew --no-daemon clean bootJar

FROM eclipse-temurin:21-jre-jammy AS runtime

# ffmpeg/ffprobe power the voice media probe (Task 3). The version is PINNED (plan §15) for
# reproducible probe behavior: 7:4.4.2-0ubuntu0.22.04.1 is the jammy-updates candidate in
# this base image (ffprobe ships in the same package). When Ubuntu supersedes the point
# release the archive drops the old one and the build fails loudly — bump the pin then.
# Pinning the whole base image digest instead would freeze JRE security updates too, a worse
# trade for a single binary we already version-fix explicitly.
RUN apt-get update \
    && apt-get install --no-install-recommends -y \
        curl \
        ffmpeg=7:4.4.2-0ubuntu0.22.04.1 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system interviewpilot \
    && useradd --system --gid interviewpilot --home-dir /app --shell /usr/sbin/nologin interviewpilot \
    && mkdir -p /app/data/knowledge \
    && chown -R interviewpilot:interviewpilot /app

WORKDIR /app
COPY --from=build --chown=interviewpilot:interviewpilot /workspace/build/libs/interview-pilot-*.jar app.jar

USER interviewpilot
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=45s --retries=6 \
  CMD curl --fail --silent http://localhost:8080/actuator/health >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
