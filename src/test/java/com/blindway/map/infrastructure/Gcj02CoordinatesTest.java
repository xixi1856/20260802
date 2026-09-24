package com.blindway.map.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Gcj02CoordinatesTest {

    @Test
    void convertsBeijingGcj02ToWgs84WithinMeterScaleTolerance() {
        var point = Gcj02Coordinates.toWgs84(116.403372, 39.917930);

        assertThat(point.longitude()).isCloseTo(116.397128, within(0.00002));
        assertThat(point.latitude()).isCloseTo(39.916527, within(0.00002));
    }

    @Test
    void keepsCoordinatesOutsideChinaUnchanged() {
        var point = Gcj02Coordinates.toWgs84(-0.1276, 51.5072);

        assertThat(point.longitude()).isEqualTo(-0.1276);
        assertThat(point.latitude()).isEqualTo(51.5072);
    }

    private org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
