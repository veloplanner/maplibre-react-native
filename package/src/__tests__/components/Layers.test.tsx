import { render } from "@testing-library/react-native";

import { Layer, type LayerProps } from "@/components/layer/Layer";

describe("Layer Components", () => {
  const layerTestCases: {
    name: string;
    props: LayerProps;
  }[] = [
    {
      name: "BackgroundLayer",
      props: {
        type: "background",
      },
    },
    {
      name: "CircleLayer",
      props: {
        type: "circle",
      },
    },
    {
      name: "FillExtrusionLayer",
      props: {
        type: "fill-extrusion",
      },
    },
    {
      name: "FillLayer",
      props: {
        type: "fill",
      },
    },
    {
      name: "HeatmapLayer",
      props: {
        type: "heatmap",
      },
    },
    {
      name: "LineLayer",
      props: {
        type: "line",
      },
    },
    {
      name: "RasterLayer",
      props: {
        type: "raster",
      },
    },
    {
      name: "SymbolLayer",

      props: {
        type: "symbol",
      },
    },
  ];

  layerTestCases.forEach(({ name, props: { type } }) => {
    describe(name, () => {
      test("renders correctly with custom props", () => {
        const testProps = {
          id: "custom-id",
          source: "custom-source",
          "source-layer": "custom-source-layer",
          beforeId: "custom-before-id",
          afterId: "custom-after-id",
          layerIndex: 0,
          filter: ["==", "arbitraryFilter", true],
          minzoom: 3,
          maxzoom: 8,
          layout: { visibility: "none" },
        } as const;

        // Skip source/sourceLayer for background layer
        const layerProps =
          type === "background"
            ? {
                type,
                id: testProps.id,
                beforeId: testProps.beforeId,
                afterId: testProps.afterId,
                layerIndex: testProps.layerIndex,
                minzoom: testProps.minzoom,
                maxzoom: testProps.maxzoom,
                layout: testProps.layout,
              }
            : {
                type,
                ...testProps,
              };

        const { queryByTestId } = render(<Layer {...(layerProps as any)} />);
        const layer = queryByTestId(`mlrn-${type}-layer`);
        const { props } = layer!;

        expect(props.id).toStrictEqual(testProps.id);
        if (type !== "background") {
          expect(props.source).toStrictEqual(testProps.source);
          expect(props.sourceLayer).toStrictEqual(testProps["source-layer"]);
          expect(props.filter).toStrictEqual(testProps.filter);
        }
        expect(props.beforeId).toStrictEqual(testProps.beforeId);
        expect(props.afterId).toStrictEqual(testProps.afterId);
        expect(props.layerIndex).toStrictEqual(testProps.layerIndex);
        expect(props.minzoom).toStrictEqual(testProps.minzoom);
        expect(props.maxzoom).toStrictEqual(testProps.maxzoom);
        expect(props.reactStyle).toStrictEqual({
          visibility: {
            styletype: "constant",
            stylevalue: { type: "string", value: testProps.layout.visibility },
          },
        });
      });
    });
  });

  describe("style memoization", () => {
    const renderLineLayer = (paint: { "line-opacity": number }) => (
      <Layer
        type="line"
        id="memo-layer"
        source="memo-source"
        paint={{ "line-color": "red", ...paint }}
        layout={{ "line-cap": "round" }}
      />
    );

    test("keeps reactStyle identity when style props are value-equal", () => {
      const { queryByTestId, rerender } = render(
        renderLineLayer({ "line-opacity": 0.9 }),
      );
      const firstReactStyle =
        queryByTestId("mlrn-line-layer")!.props.reactStyle;

      // Fresh but value-equal inline style objects must not produce a new
      // native style prop.
      rerender(renderLineLayer({ "line-opacity": 0.9 }));

      expect(queryByTestId("mlrn-line-layer")!.props.reactStyle).toBe(
        firstReactStyle,
      );
    });

    test("recomputes reactStyle when style values change", () => {
      const { queryByTestId, rerender } = render(
        renderLineLayer({ "line-opacity": 0.9 }),
      );
      const firstReactStyle =
        queryByTestId("mlrn-line-layer")!.props.reactStyle;

      rerender(renderLineLayer({ "line-opacity": 0.3 }));

      const nextReactStyle = queryByTestId("mlrn-line-layer")!.props.reactStyle;
      expect(nextReactStyle).not.toBe(firstReactStyle);
      expect(nextReactStyle).not.toStrictEqual(firstReactStyle);
    });
  });
});
