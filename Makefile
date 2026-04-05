.PHONY: build run clean test setup check jar install

# Environment setup
export PATH := /opt/homebrew/bin:$(PATH)

build:
	@echo "Building WNIR mod..."
	./gradlew --warning-mode all build

clean:
	@echo "Cleaning build artifacts..."
	./gradlew clean

test:
	@echo "Running tests..."
	./gradlew test

run:
	@echo "Running in dev environment..."
	./gradlew runClient

setup:
	@echo "Setting up development environment..."
	gradle wrapper --gradle-version=8.14
	chmod +x gradlew

check:
	@echo "Checking mod integrity..."
	./gradlew check

jar: build
	@echo "Jar location:"
	@ls -la build/libs/*.jar 2>/dev/null || true

install: jar
	@echo "Installing to PrismLauncher..."
	@cp build/libs/wnir-1.21.11-1.0.0.jar "/Users/vany/Library/Application Support/PrismLauncher/instances/VanyLLa3d/minecraft/mods/"
	@echo "Installed."
