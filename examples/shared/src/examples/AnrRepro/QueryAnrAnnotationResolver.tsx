import { Camera, Map } from "@maplibre/maplibre-react-native";
import { useNavigation } from "@react-navigation/native";
import { Text, View } from "react-native";

import { Bubble } from "@/components/Bubble";
import { MAPLIBRE_DEMO_STYLE } from "@/constants/MAPLIBRE_DEMO_STYLE";

/**
 * Reproduces an ANR: the SymbolManager's AnnotationManager registers a
 * MapClickResolver that runs a synchronous queryRenderedFeatures on EVERY tap
 * and long-press — on builds where the SymbolManager is created eagerly at
 * style load, a completely plain map (no sources, no annotations, no handlers)
 * is affected.
 *
 * Mechanism — identical timing to QueryAnrPressableSource, different query site
 * (AnnotationManager.queryMapForFeatures → queryRenderedFeaturesForPoint): 1.
 * onSingleTapConfirmed fires ~300 ms after finger-up and survives detach. 2.
 * `onTouchEnd` pushes a screen (no animation) at +120 ms; the covered screen is
 * detached (not destroyed) and its render thread exits. 3. The resolver's
 * synchronous query is posted to the dead render thread → the main thread
 * blocks forever on its future → ANR.
 *
 * Steps: single-tap the map once. On affected builds the app freezes; on builds
 * with the lazy SymbolManager no resolver exists and nothing happens.
 *
 * Note: this screen must NOT contain a PointAnnotation — adding one creates the
 * SymbolManager (lazily on fixed builds) and its resolver-first click priority
 * re-introduces the unguarded query path for annotation users.
 */

// Root example list route. The navigator disables transition animations, so
// the push detaches this screen inside the ~300 ms window.
const PUSH_TARGET_ROUTE = "MapLibre React Native";

export function QueryAnrAnnotationResolver() {
  const navigation = useNavigation<any>();

  return (
    <View
      style={{ flex: 1 }}
      onTouchEnd={() => {
        console.log("[ANR repro] touch up — detaching map in 120 ms");
        setTimeout(() => {
          console.log("[ANR repro] pushing screen now (map detaches)");
          navigation.push(PUSH_TARGET_ROUTE);
        }, 120);
      }}
    >
      <Map mapStyle={MAPLIBRE_DEMO_STYLE}>
        <Camera zoom={2} center={[0, 0]} />
      </Map>

      <Bubble>
        <Text>
          Single-tap the map once. On builds with an eagerly created
          SymbolManager the app freezes (ANR dialog ~5 s after the next input).
        </Text>
      </Bubble>
    </View>
  );
}
