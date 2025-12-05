# Base image for the main stage
FROM debian:trixie-slim AS base

# Environment
ENV DEBIAN_FRONTEND=noninteractive \
    TZ=Europe/Berlin \
    LANG=en_US.UTF-8 \
    LANGUAGE=en_US:en \
    LC_ALL=en_US.UTF-8

# Install build tools and dependencies common to all stages, with the necessary locale and timezone setup
# Last line of the installation are the packages required for building and running Verilator, see 
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates tzdata locales wget curl \
    git build-essential clang lld cmake make g++ python3-dev pkg-config autoconf ccache openjdk-21-jdk \
    help2man perl mold libgoogle-perftools-dev numactl perl-doc libfl2 libfl-dev zlib1g zlib1g-dev flex bison \
    && echo "en_US.UTF-8 UTF-8" > /etc/locale.gen \
    && locale-gen \
    && ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone \
    && rm -rf /var/lib/apt/lists/*

# Create unprivileged user with home for subsequent stages
RUN groupadd -g 1000 soct && useradd -m -u 1000 -g 1000 -s /bin/bash soct
USER soct
ENV HOME=/home/soct \
    SCRIPTS_DIR=/home/soct/scripts \
    RISCV_TOOLS=/home/soct/tools/vendor/riscv-none-elf-gcc \
    VERILATOR_ROOT=/home/soct/tools/verilator \
    CIRCT_ROOT=/home/soct/tools/circt \
    CIRCT_BUILD=/home/soct/tools/circt/build-Release \
    CIRCT_INSTALL=/home/soct/tools/circt/build-Release/install

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

# Verilator Build Stage
FROM base AS verilator-builder
ARG VERILATOR_TAG=v5.042
COPY --chown=soct:soct /shared/cmake/install-verilator.cmake ${SCRIPTS_DIR}/install-verilator.cmake

RUN git clone --branch ${VERILATOR_TAG} --depth=1 https://github.com/verilator/verilator.git ${VERILATOR_ROOT} \
    && cmake -DVERILATOR_SOURCE=${VERILATOR_ROOT}  \
    -DVERILATOR_INSTALL=${VERILATOR_ROOT}/artifact \
    -P ${SCRIPTS_DIR}/install-verilator.cmake

# CIRCT Build Stage
FROM base AS circt-builder
ARG CIRCT_TAG=firtool-1.136.0
RUN git clone --branch ${CIRCT_TAG} --depth=1 --recurse-submodules https://github.com/llvm/circt.git ${CIRCT_ROOT} && \
    mkdir -p ${CIRCT_BUILD}/circt ${CIRCT_BUILD}/llvm ${CIRCT_INSTALL}

# Build LLVM
WORKDIR ${CIRCT_BUILD}/llvm
RUN cmake "${CIRCT_ROOT}/llvm/llvm" \
    -DCMAKE_BUILD_TYPE="Release" \
    -DCMAKE_C_COMPILER=clang \
    -DCMAKE_CXX_COMPILER=clang++ \
    -DCMAKE_INSTALL_PREFIX=${CIRCT_INSTALL}\
    -DLLVM_BUILD_EXAMPLES=OFF \
    -DLLVM_ENABLE_ASSERTIONS=OFF \
    -DLLVM_ENABLE_BINDINGS=OFF \
    -DLLVM_ENABLE_OCAMLDOC=OFF \
    -DLLVM_ENABLE_PROJECTS='mlir' \
    -DLLVM_INSTALL_UTILS=ON \
    -DLLVM_OPTIMIZED_TABLEGEN=ON \
    -DLLVM_STATIC_LINK_CXX_STDLIB=ON \
    -DLLVM_TARGETS_TO_BUILD="host" \
    -DLLVM_ENABLE_LLD=ON \
    -DLLVM_BUILD_SHARED_LIBS=ON \
    -DLLVM_ENABLE_RTTI=ON && \
    cmake --build ${CIRCT_BUILD}/llvm --target install --parallel $(nproc)

WORKDIR ${CIRCT_BUILD}/circt
RUN cmake "${CIRCT_ROOT}" \
    -DCMAKE_BUILD_TYPE="Release" \
    -DCMAKE_C_COMPILER=clang \
    -DCMAKE_CXX_COMPILER=clang++ \
    -DCMAKE_INSTALL_PREFIX=${CIRCT_INSTALL}  \
    -DLLVM_ENABLE_ASSERTIONS=OFF \
    -DMLIR_DIR=${CIRCT_BUILD}/llvm/lib/cmake/mlir \
    -DLLVM_DIR=${CIRCT_BUILD}/llvm/lib/cmake/llvm \
    -DLLVM_STATIC_LINK_CXX_STDLIB=ON \
    -DVERILATOR_DISABLE=ON \
    -DLLVM_EXTERNAL_LIT=${CIRCT_BUILD}/llvm/bin \
    -DLLVM_ENABLE_LLD=ON && \
    cmake --build ${CIRCT_BUILD}/circt --target install --parallel $(nproc)

# RISC-V compiler download stage
FROM base AS riscv-builder
ARG XPACK_TAG=15.2.0-1
COPY --chown=soct:soct /shared/cmake/install-riscv-tools.cmake ${SCRIPTS_DIR}/install-riscv-tools.cmake

RUN cmake -DRISCV_TOOLS_VERSION=${XPACK_TAG} \
    -DRISCV_TOOLS=${RISCV_TOOLS} \
    -P ${SCRIPTS_DIR}/install-riscv-tools.cmake

# Main Build Stage
FROM base AS final

# Circt
COPY --chown=soct:soct --from=circt-builder ${CIRCT_ROOT} ${CIRCT_ROOT}

# Verilator
COPY --chown=soct:soct --from=verilator-builder ${VERILATOR_ROOT} ${VERILATOR_ROOT}

# RISC-V compiler
COPY --chown=soct:soct --from=riscv-builder ${RISCV_TOOLS} ${RISCV_TOOLS}

# Scala
COPY --chown=soct:soct --from=scala-builder ${HOME}/.local/share/coursier ${HOME}/.local/share/coursier
COPY --chown=soct:soct --from=scala-builder ${HOME}/.cache/coursier ${HOME}/.cache/coursier
ENV PATH=${HOME}/.local/share/coursier/bin:${PATH}

# Add Packages only needed at runtime here:
USER root
RUN apt-get update && apt-get install -y --no-install-recommends \
    device-tree-compiler \
    && rm -rf /var/lib/apt/lists/*
USER soct