import {
  Camera,
  Map,
  type TrackUserLocation,
} from "@maplibre/maplibre-react-native";
import { useState } from "react";
import { Text, View } from "react-native";

import { Bubble } from "@/components/Bubble";
import { MAPLIBRE_DEMO_STYLE } from "@/constants/MAPLIBRE_DEMO_STYLE";
import { OSM_VECTOR_STYLE } from "@/constants/OSM_VECTOR_STYLE";

/**
 * Reproduces a crash: NullPointerException in MLRNCamera.setTrackUserLocation
 * when the prop update lands while the style is (re)loading.
 *
 * Mechanism: MapLibreMap.getStyle() returns null until the style has fully
 * loaded — during the initial load and during every style switch. On affected
 * builds, a `trackUserLocation` update in that window crashes on `style!!` .
 *
 * Steps: tap the bubble. It swaps `mapStyle` (starting a style reload) and sets
 * `trackUserLocation` 60 ms and 180 ms later, inside the loading window. On
 * affected builds the app crashes with a NullPointerException (a redbox in dev
 * builds); on fixed builds the update is applied via the async getStyle
 * callback once loading finishes.
 *
 * The style URL gets a throwaway query parameter on every swap: once both
 * styles are in MapLibre's ambient cache a reload can finish faster than the
 * toggle delay and the crash window closes — cache-busting keeps the style JSON
 * on the network path so the window stays open.
 */

const STYLES = [MAPLIBRE_DEMO_STYLE, OSM_VECTOR_STYLE];

export function TrackUserLocationStyleReloadNpe() {
  const [styleCounter, setStyleCounter] = useState(0);
  const [trackUserLocation, setTrackUserLocation] = useState<
    TrackUserLocation | undefined
  >(undefined);

  const mapStyle = `${STYLES[styleCounter % STYLES.length]}?anrRepro=${styleCounter}`;

  return (
    <View style={{ flex: 1 }}>
      <Map mapStyle={mapStyle}>
        <Camera
          zoom={2}
          center={[0, 0]}
          trackUserLocation={trackUserLocation}
        />
      </Map>

      <Bubble
        onPress={() => {
          console.log("[NPE repro] swapping style + toggling tracking");
          setStyleCounter((it) => it + 1);

          const next = trackUserLocation ? undefined : "default";
          [60, 180].forEach((delay) => {
            setTimeout(() => setTrackUserLocation(next), delay);
          });
        }}
      >
        <Text>
          Tap here: swaps the style (cache-busted) and toggles trackUserLocation
          during the reload. Affected builds crash (NPE); repeat if nothing
          happens. Currently tracking: {trackUserLocation ?? "off"}
        </Text>
      </Bubble>
    </View>
  );
}
