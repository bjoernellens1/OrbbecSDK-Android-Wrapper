FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

# Install system dependencies (no system cmake — we use the SDK-managed one)
RUN apt-get update && apt-get install -y \
    openjdk-17-jdk \
    wget \
    unzip \
    git \
    ninja-build \
    build-essential \
    python3 \
    && rm -rf /var/lib/apt/lists/*

# Set JAVA_HOME explicitly so sdkmanager and Gradle can find it
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# Android SDK environment
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=$ANDROID_SDK_ROOT
ENV PATH=$PATH:$JAVA_HOME/bin:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools

# Download Android Command Line Tools
RUN mkdir -p $ANDROID_SDK_ROOT/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
         -O /tmp/cmdline-tools.zip && \
    unzip -q /tmp/cmdline-tools.zip -d $ANDROID_SDK_ROOT/cmdline-tools && \
    mv $ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools $ANDROID_SDK_ROOT/cmdline-tools/latest && \
    rm /tmp/cmdline-tools.zip

# Pre-write SDK license hashes — more reliable than piping 'yes' to sdkmanager
RUN mkdir -p $ANDROID_SDK_ROOT/licenses && \
    printf "24333f8a63b6825ea9c5514f83c2829b004d1fee\n8933bad161af4408b1c1ac0be43914b782921e9c\nd56f5187479451eabf01fb78af6dfcb131a6481e" \
        > $ANDROID_SDK_ROOT/licenses/android-sdk-license && \
    printf "84831b9409646a918e30573bab4c9c91346d8abd" \
        > $ANDROID_SDK_ROOT/licenses/android-sdk-preview-license

# Install SDK components
RUN sdkmanager \
    "platforms;android-35" \
    "build-tools;35.0.0" \
    "ndk;21.4.7075529" \
    "cmake;3.22.1" \
    "platform-tools"

ENV ANDROID_NDK_HOME=$ANDROID_SDK_ROOT/ndk/21.4.7075529

WORKDIR /workspace

CMD ["./gradlew", "assembleDebug"]
