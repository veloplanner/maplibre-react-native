import {
  Camera,
  GeoJSONSource,
  Layer,
  Map,
} from "@maplibre/maplibre-react-native";
import { useNavigation } from "@react-navigation/native";
import type { FeatureCollection } from "geojson";
import { Text, View } from "react-native";

import { Bubble } from "@/components/Bubble";
import { MAPLIBRE_DEMO_STYLE } from "@/constants/MAPLIBRE_DEMO_STYLE";

/**
 * Reproduces an ANR: synchronous queryRenderedFeatures posted to a dead render
 * thread (via the view's own onMapClick / pressable-source hit test).
 *
 * Mechanism: 1. A source with `onPress` makes MLRNMapView.onMapClick run a
 * synchronous queryRenderedFeatures (default 44×44 hitbox) on every confirmed
 * tap. 2. GestureDetector delivers onSingleTapConfirmed ~300 ms after finger-up
 * (double-tap timeout) from a Handler message that survives view detach. 3.
 * `onTouchEnd` below pushes another screen 120 ms after finger-up. The pushed
 * route attaches without animation, so react-native-screens detaches this
 * screen — without destroying it — before the confirmed tap fires.
 * MapLibreSurfaceView.onDetachedFromWindow exits the render thread permanently.
 * 4. The delayed tap then posts the query to the dead render thread and the
 * main thread blocks forever on its future → ANR.
 *
 * Steps: single-tap the map once. On affected builds the app freezes; the
 * system ANR dialog appears ~5 s after any further input.
 */

// Root example list route. The navigator disables transition animations, so
// the push detaches this screen inside the ~300 ms window.
const PUSH_TARGET_ROUTE = "MapLibre React Native";

const POINTS: FeatureCollection = {
  type: "FeatureCollection",
  features: [
    {
      type: "Feature",
      geometry: { type: "Point", coordinates: [0, 0] },
      properties: {},
    },
  ],
};

export function QueryAnrPressableSource() {
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
      <Map
        mapStyle={MAPLIBRE_DEMO_STYLE}
        onPress={() => console.log("[ANR repro] map onPress fired (no hang)")}
      >
        <Camera zoom={2} center={[0, 0]} />

        <GeoJSONSource
          id="anr-repro-source"
          data={POINTS}
          onPress={() => console.log("[ANR repro] source onPress fired")}
        >
          <Layer
            type="circle"
            id="anr-repro-circle"
            paint={{ "circle-radius": 12, "circle-color": "red" }}
          />
        </GeoJSONSource>
      </Map>

      <Bubble>
        <Text>
          Single-tap the map once. On affected builds the app freezes (ANR
          dialog ~5 s after the next input).
        </Text>
      </Bubble>
    </View>
  );
}
