# Base image for the main stage
FROM debian:trixie-slim AS base

# Environment
ENV DEBIAN_FRONTEND=noninteractive \
    TZ=Europe/Berlin \
    LANG=en_US.UTF-8 \
    LANGUAGE=en_US:en \
    LC_ALL=en_US.UTF-8

# Install base and build tooling
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates tzdata locales \
    git wget curl \
    build-essential \
    clang lld \
    cmake make \
    device-tree-compiler \
    openjdk-21-jdk \
    g++ \
    python3-dev \
    pkg-config \
    autoconf \
    help2man perl flex bison ccache mold libgoogle-perftools-dev numactl perl-doc libfl2 libfl-dev zlib1g zlib1g-dev \
 && echo "en_US.UTF-8 UTF-8" > /etc/locale.gen \
 && locale-gen \
 && ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone \
 && rm -rf /var/lib/apt/lists/*

# Create unprivileged user with home
RUN groupadd -g 1000 soct && useradd -m -u 1000 -g 1000 -s /bin/bash soct
ENV HOME=/home/soct
USER soct

# Install Java/Scala via Coursier (user-local)
FROM base AS scala-builder
ARG CS_VERSION=v2.1.24
WORKDIR /tmp
RUN set -eux; \
    arch="$(uname -m)"; \
    if [ "$arch" = "x86_64" ]; then \
      curl -fL "https://github.com/coursier/coursier/releases/download/${CS_VERSION}/cs-x86_64-pc-linux.gz" | gzip -d > cs; \
    else \
      curl -fL "https://github.com/VirtusLab/coursier-m1/releases/download/${CS_VERSION}/cs-aarch64-pc-linux.gz" | gzip -d > cs; \
    fi; \
    chmod +x cs; \
    yes | ./cs setup

# CIRCT Build Stage
FROM base AS circt-builder
ARG CIRCT_TAG=firtool-1.136.0
COPY --chmod=0755 /scripts/install-deps/install-circt.sh /tmp/scripts/install-circt.sh

# Install CIRCT
RUN git clone --branch ${CIRCT_TAG} --depth=1 --recurse-submodules https://github.com/llvm/circt.git ${HOME}/tools/circt
RUN bash /tmp/scripts/install-circt.sh ${HOME}/tools/circt Release

# Verilator Build Stage
FROM base AS verilator-builder
ARG VERILATOR_TAG=v5.042
COPY --chmod=0755 /scripts/install-deps/install-verilator.sh /tmp/scripts/install-verilator.sh

RUN git clone --branch ${VERILATOR_TAG} https://github.com/verilator/verilator.git ${HOME}/tools/verilator \
 && bash /tmp/scripts/install-verilator.sh ${HOME}/tools/verilator --quiet

# RISC-V compiler download stage
FROM base AS riscv-builder

COPY --chmod=0755 /scripts/install-deps/install-rv-compiler.sh /tmp/scripts/install-rv-compiler.sh
COPY /buildtools/cmake/riscv-toolchain-shipped.cmake /tmp/scripts/riscv-toolchain-shipped.cmake
COPY /buildtools/cmake/_riscv-toolchain.cmake /tmp/scripts/_riscv-toolchain.cmake

RUN cmake -DVENDOR_DIR=${HOME}/tools/vendor -DSCRIPTS_DIR=/tmp/scripts -P /tmp/scripts/riscv-toolchain-shipped.cmake

# Main Build Stage
FROM base AS final

# Circt
COPY --chown=soct:soct --from=circt-builder ${HOME}/tools/circt ${HOME}/tools/circt
ENV CIRCT_INSTALL_DIR=${HOME}/tools/circt/build-Release/install

# Verilator
COPY --chown=soct:soct --from=verilator-builder ${HOME}/tools/verilator ${HOME}/tools/verilator
ENV VERILATOR_ROOT=${HOME}/tools/verilator

# RISC-V compiler
COPY --chown=soct:soct --from=riscv-builder ${HOME}/tools/vendor/riscv-none-elf-gcc ${HOME}/tools/vendor/riscv-none-elf-gcc
ENV RISCV_TOOLS=${HOME}/tools/vendor/riscv-none-elf-gcc

# Scala
COPY --chown=soct:soct --from=scala-builder ${HOME}/.local/share/coursier ${HOME}/.local/share/coursier
COPY --chown=soct:soct --from=scala-builder ${HOME}/.cache/coursier ${HOME}/.cache/coursier
ENV PATH=${HOME}/.local/share/coursier/bin:${PATH}