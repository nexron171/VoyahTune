export NDK_CC=$HOME/Android/Sdk/ndk/29.0.13113456/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android33-clang

CGO_ENABLED=1 GOOS=android GOARCH=arm64 CC=$NDK_CC go build -o loaderFrida main.go
