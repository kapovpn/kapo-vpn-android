BUILDDIR ?= $(CURDIR)/build
DESTDIR ?= $(CURDIR)/out
NDK_GO_ARCH_MAP_x86 := 386
NDK_GO_ARCH_MAP_x86_64 := amd64
NDK_GO_ARCH_MAP_arm := arm
NDK_GO_ARCH_MAP_arm64 := arm64
NDK_GO_ARCH_MAP_mips := mipsx
NDK_GO_ARCH_MAP_mips64 := mips64x
comma := ,
CLANG_FLAGS := --target=$(TARGET) --sysroot=$(SYSROOT)
export CGO_CFLAGS := $(CLANG_FLAGS) $(subst -mthumb,-marm,$(CFLAGS))
export CGO_LDFLAGS := $(CLANG_FLAGS) $(patsubst -Wl$(comma)--build-id=%,-Wl$(comma)--build-id=none,$(LDFLAGS)) -Wl,-soname=libwg-go.so
export GOARCH := $(NDK_GO_ARCH_MAP_$(ANDROID_ARCH_NAME))
export GOOS := android
export CGO_ENABLED := 1
export GOPATH := C:/Users/x/go
export GOMODCACHE := C:/Users/x/go/pkg/mod
export GOCACHE := C:/Users/x/go/cache
default: $(DESTDIR)/libwg-go.so
$(DESTDIR)/libwg-go.so: go.mod
	go build -tags linux -ldflags="-X github.com/amnezia-vpn/amneziawg-go/ipc.socketDirectory=/data/data/$(ANDROID_PACKAGE_NAME)/cache/amneziawg -buildid= -extldflags=-Wl,-z,max-page-size=16384" -v -trimpath -buildvcs=false -o "$@" -buildmode c-shared .
.DELETE_ON_ERROR:
