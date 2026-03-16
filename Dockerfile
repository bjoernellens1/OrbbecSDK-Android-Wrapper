# Use Ubuntu 24.04 as requested
FROM ubuntu:24.04

# Avoid prompts from apt
ENV DEBIAN_FRONTEND=noninteractive

# Install dependencies
RUN apt-get update && apt-get install -y \
    openjdk-17-jdk \
    wget \
    unzip \
    git \
    cmake \
    ninja-build \
    build-essential \
    python3 \
    && rm -rf /var/lib/apt/lists/*

# Set up Android SDK environment
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=$ANDROID_SDK_ROOT
ENV PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools

# Download Android Command Line Tools
RUN mkdir -p $ANDROID_SDK_ROOT/cmdline-tools && \
    wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/cmdline-tools.zip && \
    unzip /tmp/cmdline-tools.zip -d $ANDROID_SDK_ROOT/cmdline-tools && \
    mv $ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools $ANDROID_SDK_ROOT/cmdline-tools/latest && \
    rm /tmp/cmdline-tools.zip

# Accept licenses and install platform components
RUN yes | sdkmanager --licenses && \
    sdkmanager "platforms;android-35" \
               "build-tools;35.0.0" \
               "ndk;21.4.7075529" \
               "cmake;3.22.1" \
               "platform-tools"

# Set up NDK environment
ENV ANDROID_NDK_HOME=$ANDROID_SDK_ROOT/ndk/21.4.7075529

# Set working directory
WORKDIR /workspace

# Default command
CMD ["./gradlew", "assembleDebug"]
