package com.tacz.guns.compat.oculus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OculusCompatTest {
    @AfterEach
    void resetApi() {
        LiveApi.current = new LiveApi();
        ThrowingFactory.failure = null;
    }

    @Test
    void absentProviderDoesNotLookUpAnyApiClass() {
        ClassLoader forbiddenLookup = new ClassLoader(null) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) {
                throw new AssertionError("Absent provider attempted class lookup: " + name);
            }
        };
        assertNull(OculusCompat.discover(false, forbiddenLookup));
    }

    @Test
    void declaredProviderWithMissingApiIsNotTreatedAsAbsent() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> OculusCompat.discover(true, new ClassLoader(null) { }));
        assertInstanceOf(ClassNotFoundException.class, exception.getCause());
        assertTrue(exception.getMessage().contains("net.irisshaders.iris.api.v0.IrisApi"));
    }

    @Test
    void queriesReadLiveFlagsAndReplacementInstancesThroughTheSameBinding() {
        OculusCompat.ApiMethods api = OculusCompat.bind(LiveApi.class);
        assertFalse(api.query(api.shadowPass()));
        assertFalse(api.query(api.shaderPack()));

        LiveApi.current.shadow = true;
        LiveApi.current.pack = true;
        assertTrue(api.query(api.shadowPass()));
        assertTrue(api.query(api.shaderPack()));

        LiveApi.current = new LiveApi();
        assertFalse(api.query(api.shadowPass()));
        assertFalse(api.query(api.shaderPack()));
    }

    @Test
    void missingQueryIsAnExplicitContractError() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> OculusCompat.bind(MissingQuery.class));
        assertInstanceOf(NoSuchMethodException.class, exception.getCause());
    }

    @Test
    void boxedQueryResultIsNotAcceptedAsThePublicApiContract() {
        assertThrows(IllegalStateException.class, () -> OculusCompat.bind(BoxedQuery.class));
    }

    @Test
    void staticQueryIsNotAcceptedAsThePublicApiContract() {
        assertThrows(IllegalStateException.class, () -> OculusCompat.bind(StaticQuery.class));
    }

    @Test
    void nonStaticFactoryIsNotAcceptedAsThePublicApiContract() {
        assertThrows(IllegalStateException.class, () -> OculusCompat.bind(InstanceFactory.class));
    }

    @Test
    void unrelatedFactoryReturnTypeIsNotAcceptedAsThePublicApiContract() {
        assertThrows(IllegalStateException.class, () -> OculusCompat.bind(UnrelatedFactory.class));
    }

    @Test
    void nullFactoryResultDoesNotBecomeFalse() {
        OculusCompat.ApiMethods api = OculusCompat.bind(LiveApi.class);
        LiveApi.current = null;
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> api.query(api.shadowPass()));
        assertTrue(exception.getMessage().contains("getInstance"));
    }

    @Test
    void queryFailuresRetainTheirOriginalCause() {
        OculusCompat.ApiMethods api = OculusCompat.bind(LiveApi.class);
        RuntimeException failure = new IllegalArgumentException("broken shadow state");
        LiveApi.current.failure = failure;
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> api.query(api.shadowPass()));
        assertSame(failure, exception.getCause());
        assertTrue(exception.getMessage().contains("isRenderingShadowPass"));
    }

    @Test
    void factoryFailuresRetainTheirOriginalCause() {
        OculusCompat.ApiMethods api = OculusCompat.bind(ThrowingFactory.class);
        RuntimeException failure = new IllegalArgumentException("broken provider factory");
        ThrowingFactory.failure = failure;
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> api.query(api.shadowPass()));
        assertSame(failure, exception.getCause());
        assertTrue(exception.getMessage().contains("getInstance"));
    }

    @Test
    void declaredProviderLinkageFailureDoesNotBecomeAbsent() {
        LinkageError failure = new NoClassDefFoundError("provider dependency");
        ClassLoader brokenLoader = new ClassLoader(null) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) {
                throw failure;
            }
        };
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> OculusCompat.discover(true, brokenLoader));
        assertSame(failure, exception.getCause());
    }

    public static class LiveApi {
        static LiveApi current = new LiveApi();
        boolean shadow;
        boolean pack;
        RuntimeException failure;

        public static LiveApi getInstance() { return current; }
        public boolean isRenderingShadowPass() {
            if (failure != null) {
                throw failure;
            }
            return shadow;
        }
        public boolean isShaderPackInUse() { return pack; }
    }

    public static class MissingQuery {
        public static MissingQuery getInstance() { return new MissingQuery(); }
        public boolean isRenderingShadowPass() { return false; }
    }

    public static class BoxedQuery {
        public static BoxedQuery getInstance() { return new BoxedQuery(); }
        public Boolean isRenderingShadowPass() { return false; }
        public boolean isShaderPackInUse() { return false; }
    }

    public static class StaticQuery {
        public static StaticQuery getInstance() { return new StaticQuery(); }
        public static boolean isRenderingShadowPass() { return false; }
        public boolean isShaderPackInUse() { return false; }
    }

    public static class InstanceFactory {
        public InstanceFactory getInstance() { return this; }
        public boolean isRenderingShadowPass() { return false; }
        public boolean isShaderPackInUse() { return false; }
    }

    public static class UnrelatedFactory {
        public static Object getInstance() { return new Object(); }
        public boolean isRenderingShadowPass() { return false; }
        public boolean isShaderPackInUse() { return false; }
    }

    public static class ThrowingFactory {
        static RuntimeException failure;
        public static ThrowingFactory getInstance() { throw failure; }
        public boolean isRenderingShadowPass() { return false; }
        public boolean isShaderPackInUse() { return false; }
    }
}
