package com.blindway.trip.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.blindway.common.api.ApiException;
import com.blindway.trip.infrastructure.TrackPointMatchRow;
import com.blindway.trip.infrastructure.TripMapper;
import com.blindway.trip.infrastructure.TripRow;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TripServiceTest {

    @Mock
    private TripMapper mapper;

    @Test
    void startsTripForOwnedActiveDevice() {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        when(mapper.countOwnedActiveDevice(deviceId, userId)).thenReturn(1);
        TripService service = new TripService(mapper);

        var response = service.start(userId, deviceId);

        ArgumentCaptor<TripRow> captor = ArgumentCaptor.forClass(TripRow.class);
        verify(mapper).insertTrip(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo("ACTIVE");
        assertThat(response.deviceId()).isEqualTo(deviceId);
    }

    @Test
    void rejectsUnownedDevice() {
        when(mapper.countOwnedActiveDevice(any(), any())).thenReturn(0);
        TripService service = new TripService(mapper);

        assertThatThrownBy(() -> service.start(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .hasMessage("设备未绑定到当前用户或不可用");
    }

    @Test
    void interpolatesLocationBetweenTrackPoints() {
        UUID deviceId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        Instant eventTime = Instant.parse("2026-08-13T10:00:03Z");
        TripRow trip = new TripRow(
                tripId,
                UUID.randomUUID(),
                deviceId,
                "ACTIVE",
                eventTime.minusSeconds(60),
                null,
                eventTime.minusSeconds(60),
                eventTime.minusSeconds(60));
        when(mapper.findById(tripId)).thenReturn(java.util.Optional.of(trip));
        when(mapper.findTrackPointBefore(tripId, eventTime))
                .thenReturn(
                        java.util.Optional.of(new TrackPointMatchRow(1L, 116.0, 39.0, eventTime.minusSeconds(1), 8)));
        when(mapper.findTrackPointAfter(tripId, eventTime))
                .thenReturn(java.util.Optional.of(
                        new TrackPointMatchRow(2L, 116.002, 39.002, eventTime.plusSeconds(1), 9)));
        TripService service = new TripService(mapper);

        var match = service.resolve(deviceId, tripId, eventTime);

        assertThat(match.status()).isEqualTo("INTERPOLATED");
        assertThat(match.quality()).isEqualTo("HIGH");
        assertThat(match.longitude()).isCloseTo(116.001, org.assertj.core.data.Offset.offset(0.0000001));
        assertThat(match.latitude()).isCloseTo(39.001, org.assertj.core.data.Offset.offset(0.0000001));
        assertThat(match.timeOffsetMs()).isEqualTo(1000);
    }
}
