#!/bin/bash

# Get the directory of the current script
THIS_DIR=$(dirname "$(realpath "$0")")

# Get the root directory of the project
SOCETEER_ROOT=$(realpath "$THIS_DIR/../..")

# Get the repository information
_REPO=$(git remote get-url origin | awk -F'iti.uni-luebeck.de' '{split($2, arr, ":|/"); print arr[2] "/" arr[3];}')

# Remove spaces and .git from the repository name
REPO=$(echo "$_REPO" | sed 's/ //g' | sed 's/\.git//g')

# Set the Docker image name
REGISTRY=registry.iti.uni-luebeck.de
SIM_IMAGE=soceteer-sim
DOCKER_IMAGE=${DOCKER_IMAGE:-$REGISTRY/$REPO/$SIM_IMAGE}

# Check for --build option
if [[ " $* " == *" --build"* ]]; then
    # Build the Docker image
    echo "Building Docker image $DOCKER_IMAGE"
    # Set Docker params:
    DOCKER_PARAMS="-t $DOCKER_IMAGE --memory=12g --memory-swap=32g $SOCETEER_ROOT"

    if [[ -n "$PUSH_IMAGE" ]]; then
        # Add the --push flag to the Docker params
        DOCKER_PARAMS+=" --push"
    fi
    if [[ " $* " == *" --multiarch"* ]]; then
        # Build the Docker image with multi-architecture support. Always push to the registry.
        docker buildx build --platform linux/arm64,linux/amd64 $DOCKER_PARAMS
    else
        # Build the Docker image without multi-architecture support
        docker buildx build $DOCKER_PARAMS
    fi
fi

# Check for --pull option
if [[ " $* " == *" --pull"* ]]; then
    # Check if TOKEN_FILE and USERNAME are set
    if [[ -z "$TOKEN_FILE" || ! -f "$TOKEN_FILE" ]]; then
        echo "TOKEN_FILE is not set or does not exist. Please set it to the path of the token file."
        exit 1
    fi
    if [[ -z "$USERNAME" ]]; then
        echo "USERNAME is not set. Please set it to your username on the registry."
        exit 1
    fi
    # Pull the Docker image
    TOKEN=$(cat "$TOKEN_FILE")
    # Pull the Docker image with the token
    echo "Pulling Docker image $DOCKER_IMAGE with token from $TOKEN_FILE for user $USERNAME"
    export DOCKER_CONFIG=$(mktemp -d)
    echo '{}' > "$DOCKER_CONFIG/config.json"
    echo "$TOKEN" | docker login --password-stdin --username $USERNAME $REGISTRY
    docker pull "$DOCKER_IMAGE"
fi

# Check for --shell option
if [[ " $* " == *" --shell"* ]]; then
    # Run the Docker container with a shell
    docker run --pull always --user "$(id -u):$(id -g)" --group-add=$(id -g) -it --rm -v "$SOCETEER_ROOT:/home/soct/soceteer" -w "/home/soct/soceteer" "$DOCKER_IMAGE" /bin/bash
fi

# Check for --test-sim option (noninteractive)
if [[ " $* " == *" --test-sim"* ]]; then
    docker run --pull always --user "$(id -u):$(id -g)" --group-add=$(id -g) -i --rm -v "$SOCETEER_ROOT:/home/soct/soceteer" -w "/home/soct/soceteer" "$DOCKER_IMAGE" python3 scripts/cicd/test-sim.py $TEST_SIM_ARGS
fi

if [[ " $* " == *" --compile-soceteer"* ]]; then
    docker run --pull always --user "$(id -u):$(id -g)" --group-add=$(id -g) -i --rm -v "$SOCETEER_ROOT:/home/soct/soceteer" -w "/home/soct/soceteer" "$DOCKER_IMAGE" python3 scripts/cicd/compile-soceteer.py $SOCT_CHISEL_VERSION
fi