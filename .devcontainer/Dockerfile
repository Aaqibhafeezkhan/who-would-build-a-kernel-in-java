# The same toolchain CI uses (Ubuntu 24.04, JDK 25, QEMU, OVMF), for Codespaces or a local
# dev container. Everything else (Gradle, Limine) the build fetches itself.
FROM eclipse-temurin:25-jdk-noble

RUN apt-get update \
    && apt-get install -y --no-install-recommends qemu-system-x86 ovmf make git nodejs musl-tools binutils \
    && rm -rf /var/lib/apt/lists/*
