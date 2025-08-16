#!/bin/bash

set -e

usage() {
    echo "Usage: $0 [--build-app] [--synth] [--deploy]"
    exit 1
}

BUILD_APP=false
SYNTH=false
DEPLOY=false

if [ "$#" -eq 0 ]; then
    usage
fi

BIN_DIR=$(cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
CDK_HOME="$(realpath $BIN_DIR/../../cdk)"
APP_HOME="$(realpath $BIN_DIR/../../app)"

while [[ "$#" -gt 0 ]]; do
    case $1 in
        --build-app) BUILD_APP=true ;;
        --synth) SYNTH=true ;;
        --deploy) DEPLOY=true ;;
        *) usage ;;
    esac
    shift
done

if [ "$BUILD_APP" = true ]; then
    echo "Building the app..."
    cd $APP_HOME
    gradle build && gradle assemble-jars
fi

if [ "$SYNTH" = true ]; then
    echo "Synthesizing the CDK stack..."
    cd $CDK_HOME
    cdk synth
fi

if [ "$DEPLOY" = true ]; then
    echo "Deploying the CDK stack..."
    rsync -av --delete "$APP_HOME/build/assets" "$CDK_HOME/docker"

    cd $CDK_HOME

    # Docker needs sudo access
    cdk deploy
fi