package com.localcloud.gateway;

import com.linecorp.armeria.server.HttpService;
import com.localcloud.emulators.EmulatorBase;

import io.grpc.BindableService;
import io.grpc.ServerServiceDefinition;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ApiGateway}.
 * Uses Mockito mocks for {@link EmulatorBase}, {@link HttpService}, and {@link BindableService}.
 */
@ExtendWith(MockitoExtension.class)
class ApiGatewayTest {

    private ApiGateway gateway;

    @Mock private HttpService httpService;

    @BeforeEach
    void setUp() {
        gateway = new ApiGateway();
    }

    private EmulatorBase mockEmulator(String name) {
        EmulatorBase emulator = mock(EmulatorBase.class);
        when(emulator.getName()).thenReturn(name);
        return emulator;
    }

    // -----------------------------------------------------------------------
    // Empty gateway
    // -----------------------------------------------------------------------

    @Test
    void emptyGatewayHasZeroEmulators() {
        assertEquals(0, gateway.getEmulatorCount());
        assertTrue(gateway.getEmulators().isEmpty());
    }

    @Test
    void emptyGatewayHasNoRestRegistrations() {
        assertTrue(gateway.getRestRegistrations().isEmpty());
    }

    @Test
    void emptyGatewayHasNoGrpcRegistrations() {
        assertTrue(gateway.getGrpcRegistrations().isEmpty());
    }

    // -----------------------------------------------------------------------
    // REST registration
    // -----------------------------------------------------------------------

    @Test
    void registerRestEmulatorAppearsInEmulators() {
        EmulatorBase gcs = mockEmulator("gcs");
        gateway.registerRestEmulator("/storage/v1", gcs, httpService);

        List<EmulatorBase> emulators = gateway.getEmulators();
        assertEquals(1, emulators.size());
        assertSame(gcs, emulators.get(0));
    }

    @Test
    void registerRestEmulatorAppearsInRestRegistrations() {
        EmulatorBase gcs = mockEmulator("gcs");
        gateway.registerRestEmulator("/storage/v1", gcs, httpService);

        Map<String, ApiGateway.RestRegistration> regs = gateway.getRestRegistrations();
        assertEquals(1, regs.size());
        assertTrue(regs.containsKey("gcs"));

        ApiGateway.RestRegistration reg = regs.get("gcs");
        assertEquals("/storage/v1", reg.pathPrefix());
        assertSame(gcs, reg.emulator());
        assertSame(httpService, reg.service());
    }

    // -----------------------------------------------------------------------
    // gRPC registration
    // -----------------------------------------------------------------------

    @Test
    void registerGrpcEmulatorAppearsInEmulators() {
        EmulatorBase pubsub = mockEmulator("pubsub");
        BindableService grpcService = mock(BindableService.class);
        gateway.registerGrpcEmulator(pubsub, grpcService);

        List<EmulatorBase> emulators = gateway.getEmulators();
        assertEquals(1, emulators.size());
        assertSame(pubsub, emulators.get(0));
    }

    @Test
    void registerGrpcEmulatorAppearsInGrpcRegistrations() {
        EmulatorBase pubsub = mockEmulator("pubsub");
        BindableService svc1 = mock(BindableService.class);
        BindableService svc2 = mock(BindableService.class);
        gateway.registerGrpcEmulator(pubsub, svc1, svc2);

        Map<String, ApiGateway.GrpcRegistration> regs = gateway.getGrpcRegistrations();
        assertEquals(1, regs.size());
        assertTrue(regs.containsKey("pubsub"));

        ApiGateway.GrpcRegistration reg = regs.get("pubsub");
        assertSame(pubsub, reg.emulator());
        assertEquals(2, reg.grpcServices().size());
    }

    // -----------------------------------------------------------------------
    // getEmulator lookup
    // -----------------------------------------------------------------------

    @Test
    void getEmulatorByNameReturnsCorrectOptional() {
        EmulatorBase gcs = mockEmulator("gcs");
        gateway.registerRestEmulator("/storage/v1", gcs, httpService);

        Optional<EmulatorBase> result = gateway.getEmulator("gcs");
        assertTrue(result.isPresent());
        assertSame(gcs, result.get());
    }

    @Test
    void getEmulatorForUnknownNameReturnsEmpty() {
        Optional<EmulatorBase> result = gateway.getEmulator("nonexistent");
        assertTrue(result.isEmpty());
    }

    // -----------------------------------------------------------------------
    // Count and multiple registrations
    // -----------------------------------------------------------------------

    @Test
    void emulatorCountMatchesRegistrations() {
        EmulatorBase gcs = mockEmulator("gcs");
        EmulatorBase pubsub = mockEmulator("pubsub");

        gateway.registerRestEmulator("/storage/v1", gcs, httpService);
        gateway.registerGrpcEmulator(pubsub, mock(BindableService.class));

        assertEquals(2, gateway.getEmulatorCount());
    }

    @Test
    void multipleRegistrationsAllAccessible() {
        EmulatorBase gcs = mockEmulator("gcs");
        EmulatorBase pubsub = mockEmulator("pubsub");
        EmulatorBase firestore = mockEmulator("firestore");

        gateway.registerRestEmulator("/storage/v1", gcs, httpService);
        gateway.registerGrpcEmulator(pubsub, mock(BindableService.class));
        gateway.registerGrpcEmulator(firestore, mock(BindableService.class));

        assertEquals(3, gateway.getEmulatorCount());
        assertTrue(gateway.getEmulator("gcs").isPresent());
        assertTrue(gateway.getEmulator("pubsub").isPresent());
        assertTrue(gateway.getEmulator("firestore").isPresent());

        assertEquals(1, gateway.getRestRegistrations().size());
        assertEquals(2, gateway.getGrpcRegistrations().size());
    }

    @Test
    void restRegistrationsMapIsUnmodifiable() {
        EmulatorBase gcs = mockEmulator("gcs");
        gateway.registerRestEmulator("/storage/v1", gcs, httpService);

        Map<String, ApiGateway.RestRegistration> regs = gateway.getRestRegistrations();
        assertThrows(UnsupportedOperationException.class,
                () -> regs.put("fake", null));
    }

    @Test
    void grpcRegistrationsMapIsUnmodifiable() {
        EmulatorBase pubsub = mockEmulator("pubsub");
        gateway.registerGrpcEmulator(pubsub, mock(BindableService.class));

        Map<String, ApiGateway.GrpcRegistration> regs = gateway.getGrpcRegistrations();
        assertThrows(UnsupportedOperationException.class,
                () -> regs.put("fake", null));
    }

    @Test
    void reRegisteringSameNameOverwritesPrevious() {
        EmulatorBase gcsV1 = mockEmulator("gcs");
        EmulatorBase gcsV2 = mockEmulator("gcs");

        gateway.registerRestEmulator("/storage/v1", gcsV1, httpService);
        gateway.registerRestEmulator("/storage/v2", gcsV2, httpService);

        // Should still only have one emulator entry for "gcs"
        assertEquals(1, gateway.getEmulatorCount());
        assertSame(gcsV2, gateway.getEmulator("gcs").orElseThrow());
    }
}
