# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

MapLibre React Native is a React Native library for creating maps with MapLibre Native for Android & iOS. This project originated as a fork of rnmapbox, separating from Mapbox-specific functionality to focus on MapLibre.

**Important**: v10 uses the new architecture through the interoperability layer with known issues. v11 alpha (on the `alpha` branch) has better new architecture support.

## Development Commands

### Setup
```bash
# Use Node version from .nvmrc
nvm install

# Install dependencies (from anywhere in the workspace)
yarn install
```

### Building & Linting
```bash
# Run all linters (TypeScript + ESLint)
yarn lint

# TypeScript only
yarn lint:tsc

# ESLint only
yarn lint:eslint

# Auto-fix ESLint issues
yarn lint:eslint:fix

# Build the library (run before publishing)
yarn prepack
```

### Testing
```bash
# Run unit tests (Jest + React Native Testing Library)
yarn test

# E2E tests (Maestro)
# First: Start React Native example app on emulator/simulator
yarn examples:react-native start
# Then: Run Maestro tests
maestro test ./examples/react-native-app/e2e
```

### Codegen
```bash
# Generate native code and documentation from MapLibre style spec
yarn codegen
```

**Important**: Files in `/docs/content/components` and `/docs/content/modules` are auto-generated. Edit the TSDoc comments in source files and run `yarn codegen` to update documentation.

### Working with Examples

#### React Native App (Old Architecture)
```bash
# Run commands with prefix
yarn examples:react-native <script>

# Or cd into directory
cd examples/react-native-app

# Start dev server
yarn start  # Press 'a' for Android, 'i' for iOS

# Build and run directly
yarn android  # Android
yarn ios:pod-install && yarn ios  # iOS (run pod-install on first setup)

# Clean build
yarn purge  # Runs purge:js, purge:android, purge:ios
```

#### Expo App (New Architecture)
```bash
# Run commands with prefix
yarn examples:expo <script>

# Build and run
yarn examples:expo android
yarn examples:expo ios

# Start dev server
yarn examples:expo start

# Clean build
yarn examples:expo purge
```

## Architecture

### Project Structure

```
/src                    TypeScript source code (React components, hooks, utilities)
/android                Native Android code (Java/Kotlin)
/ios                    Native iOS code (Objective-C/Swift)
/plugin                 Expo config plugin
/scripts                Codegen for native code and docs
/examples/shared        Shared example scenes used by both example apps
/examples/expo-app      Expo example app (new architecture)
/examples/react-native-app  React Native example app (old architecture)
/docs                   Documentation website
```

### Core Components

**Map Components** (in `/src/components`):
- `MapView` - Main map component
- `Camera` - Camera/viewport control with animation support
- `UserLocation` - User location tracking and display

**Sources** (data providers for layers):
- `VectorSource` - Vector tile data
- `ShapeSource` - GeoJSON data with ref for querying features
- `RasterSource` - Raster tile data
- `ImageSource` - Single image overlay

**Layers** (visual representation):
- `FillLayer`, `LineLayer`, `CircleLayer`, `SymbolLayer` - Vector layers
- `FillExtrusionLayer` - 3D extrusion
- `RasterLayer` - Raster tiles
- `HeatmapLayer` - Heatmap visualization
- `BackgroundLayer` - Map background

**Annotations**:
- `PointAnnotation` - Custom point markers
- `MarkerView` - React Native view as marker
- `Callout` - Info popup for annotations

**Other**:
- `Light` - 3D lighting configuration
- `Images` - Register images for use in styles

### Native Bridge Architecture

- **TypeScript → Native**: React Native components use `requireNativeComponent` and `NativeModules` to communicate with native code
- **Hooks**: `useNativeBridge`, `useAbstractLayer`, `useAbstractSource` manage native refs and lifecycle
- **iOS**: ViewManagers (e.g., `MLRNMapViewManager`) and modules (e.g., `MLRNModule`) in `/ios/MLRN/`
- **Android**: ViewManagers and modules in `/android/src/main/java/org/maplibre/reactnative/`
- **Codegen**: `yarn codegen` generates style-related native code from MapLibre GL style spec

### Modules (non-component APIs)

- `LocationManager` - Location services (request permissions, get location)
- `OfflineManager` - Offline map downloads and pack management
- `SnapshotManager` - Capture map snapshots as images

### Key Patterns

1. **Layer/Source Pattern**: Layers must be children of a MapView and typically reference a source by ID. Sources provide data, layers define visualization.

2. **Ref Pattern**: Components like `MapView`, `Camera`, `ShapeSource`, `PointAnnotation`, `UserLocation` expose imperative methods via refs using `useNativeRef` hook.

3. **Style Properties**: Layer style props are typed based on MapLibre GL style spec and support expressions for data-driven styling.

4. **Animated**: Custom animation system in `/src/utils/animated/` for animating coordinates and shapes.

## Working with Native Code

### iOS Development
- Open `/examples/react-native-app/ios/MapLibreReactNativeExample.xcworkspace` in Xcode
- Library code appears under `Pods > Development Pods > maplibre-react-native`
- Native code prefix: `MLRN` (e.g., `MLRNMapView`, `MLRNCamera`)
- Format Objective-C: `clang-format -i ios/MLRN/*` (or use Xcode 16+)

### Android Development
- Open `/examples/react-native-app/android` in Android Studio
- Library code appears as `mlrn` module
- Example app appears as `app` module
- Package: `org.maplibre.reactnative`

### Upgrading MapLibre Native
1. **Android**: Update `org.maplibre.reactnative.nativeVersion` in `/android/gradle.properties`
2. **iOS**: Update `$MLRN_NATIVE_VERSION` in `/maplibre-react-native.podspec`
3. **iOS**: Update Swift package reference in `/examples/react-native-app/ios/MapLibreReactNativeExample.xcodeproj/project.pbxproj`
4. **iOS**: Delete and regenerate `Package.resolved` by running `yarn ios:pod-install` and building in Xcode
5. Update version in `/docs/content/setup/getting-started.md`

## Best Practices

### For New Features
- Add example scene in `/examples/shared` to demonstrate functionality
- Document with TSDoc comments (generates docs via `yarn codegen`)
- Add unit tests in `/src/__tests__`
- Use conventional commits for commit messages (semantic-release for changelog)

### For Native Changes
- TypeScript changes hot-reload in example apps
- Native Android/iOS changes require rebuilding the native app
- Can rebuild directly from Android Studio or Xcode when editing native code

### Testing Unreleased Versions
Use `yarn pack --out %s-%v.tgz` to create a tarball for testing in other projects. Avoid using `link` for development—use the example apps instead for faster iteration.

## Yarn Workspaces

This monorepo uses Yarn workspaces:
- Root: Library code
- `docs`: Documentation site
- `examples/expo-app`: Expo example
- `examples/react-native-app`: React Native example

Run `yarn install` from anywhere to install all workspace dependencies.

## Package Manager

Uses **Yarn 4.5.3** (via Corepack). Do NOT install via npm.
```bash
corepack enable
corepack prepare yarn@stable --activate
```

Configure your IDE using the [Yarn Editor SDKs guide](https://yarnpkg.com/getting-started/editor-sdks).
