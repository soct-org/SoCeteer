#!/usr/bin/env bash
# Part of the LLVM Project, under the Apache License v2.0 with LLVM Exceptions.
# See https://llvm.org/LICENSE.txt for license information.
# SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
#
##===----------------------------------------------------------------------===##
#
# This script builds CIRCT and makes a tarball in /build.
#
##===----------------------------------------------------------------------===##

set -e  # Exit immediately if a command exits with a non-zero status.
set -u  # Treat unset variables as an error and exit immediately.
set -o pipefail  # Consider errors in piped commands.

# Check if CIRCT directory is provided as the first argument
if [ "$#" -lt 1 ]; then
  echo "Error: CIRCT directory must be provided as the first argument."
  echo "Usage: $0 /path/to/circt"
  exit 1
fi

# Second argument is CMAKE_BUILD_TYPE (optional)
if [ "$#" -ge 2 ]; then
  CMAKE_BUILD_TYPE="$2"
else
  CMAKE_BUILD_TYPE="Release"
fi

echo "CMAKE_BUILD_TYPE: ${CMAKE_BUILD_TYPE}"

# Set CIRCT_DIR from the first argument
CIRCT_DIR="$(cd "$1" && pwd)"
echo "Using CIRCT directory: ${CIRCT_DIR}"

# Build directory
BUILD_DIR="${CIRCT_DIR}/build-${CMAKE_BUILD_TYPE}"
mkdir -p "${BUILD_DIR}"
BUILD_DIR=$(cd "${BUILD_DIR}" && pwd)

echo "Build directory: ${BUILD_DIR}"

NPROC=$(nproc)
echo "Number of processors: ${NPROC}"

# Navigate to the build directory
cd "${BUILD_DIR}"

# Create necessary subdirectories relative to CIRCT
mkdir -p circt llvm install

# Build LLVM relative to CIRCT
cd llvm
cmake "${CIRCT_DIR}/llvm/llvm" \
    -DCMAKE_BUILD_TYPE="${CMAKE_BUILD_TYPE}" \
    -DCMAKE_C_COMPILER=clang \
    -DCMAKE_CXX_COMPILER=clang++ \
    -DCMAKE_INSTALL_PREFIX=../install \
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
    -DLLVM_ENABLE_RTTI=ON
cmake --build . --target install --parallel "${NPROC}"

# Build CIRCT relative to CIRCT
cd ../circt
cmake "${CIRCT_DIR}" \
    -DMLIR_DIR=../llvm/lib/cmake/mlir \
    -DLLVM_DIR=../llvm/lib/cmake/llvm \
    -DCMAKE_C_COMPILER=clang \
    -DCMAKE_CXX_COMPILER=clang++ \
    -DLLVM_ENABLE_ASSERTIONS=OFF \
    -DCMAKE_BUILD_TYPE="${CMAKE_BUILD_TYPE}" \
    -DLLVM_STATIC_LINK_CXX_STDLIB=ON \
    -DCMAKE_INSTALL_PREFIX=../install \
    -DVERILATOR_DISABLE=ON \
    -DLLVM_EXTERNAL_LIT=../llvm/bin \
    -DLLVM_ENABLE_LLD=ON
cmake --build . --target install --parallel "${NPROC}"

# Create release tarball relative to CIRCT
cd ../install
tar --transform "s,^,circt-release/," -czf ../circt-release.tgz .

echo "Release tarball created at: ${BUILD_DIR}/circt-release.tgz"