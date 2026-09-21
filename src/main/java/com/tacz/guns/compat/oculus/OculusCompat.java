package com.tacz.guns.compat.oculus;

import net.neoforged.fml.ModList;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class OculusCompat {
    private static final String IRIS_API = "net.irisshaders.iris.api.v0.IrisApi";

    public static void initCompat() {
        api();
    }

    public static boolean isRenderShadow() {
        ApiMethods api = api();
        return api != null && api.query(api.shadowPass());
    }

    public static boolean isUsingRenderPack() {
        ApiMethods api = api();
        return api != null && api.query(api.shaderPack());
    }

    public static boolean endBatch(Object bufferSource) {
        return false;
    }

    private static ApiMethods api() {
        return Holder.API;
    }

    private static final class Holder {
        private static final ApiMethods API = discover(
                ModList.get().isLoaded("iris") || ModList.get().isLoaded("oculus"),
                OculusCompat.class.getClassLoader());
    }

    static ApiMethods discover(boolean providerPresent, ClassLoader loader) {
        if (!providerPresent) {
            return null;
        }
        try {
            return bind(Class.forName(IRIS_API, false, loader));
        } catch (ClassNotFoundException | LinkageError | SecurityException exception) {
            throw new IllegalStateException("Loaded Iris/Oculus provider has no accessible public API: " + IRIS_API, exception);
        }
    }

    static ApiMethods bind(Class<?> apiClass) {
        try {
            Method instance = apiClass.getMethod("getInstance");
            Method shadowPass = apiClass.getMethod("isRenderingShadowPass");
            Method shaderPack = apiClass.getMethod("isShaderPackInUse");
            if (!Modifier.isStatic(instance.getModifiers()) || !apiClass.isAssignableFrom(instance.getReturnType())) {
                throw new IllegalStateException("Unsupported Iris API getInstance signature: " + instance);
            }
            validateQuery(shadowPass);
            validateQuery(shaderPack);
            return new ApiMethods(apiClass, instance, shadowPass, shaderPack);
        } catch (ReflectiveOperationException | LinkageError | SecurityException exception) {
            throw new IllegalStateException("Cannot bind loaded Iris/Oculus public API: " + apiClass.getName(), exception);
        }
    }

    private static void validateQuery(Method method) {
        if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != boolean.class) {
            throw new IllegalStateException("Unsupported Iris API query signature: " + method);
        }
    }

    record ApiMethods(Class<?> apiClass, Method instance, Method shadowPass, Method shaderPack) {
        boolean query(Method method) {
            // Pack reloads and shadow passes change live state; cache methods, never the instance or flags.
            Object current = invoke(instance, null);
            if (!apiClass.isInstance(current)) {
                throw new IllegalStateException("Iris API getInstance returned no compatible instance: " + apiClass.getName());
            }
            return (boolean) invoke(method, current);
        }

        private static Object invoke(Method method, Object receiver) {
            try {
                return method.invoke(receiver);
            } catch (InvocationTargetException exception) {
                throw new IllegalStateException("Iris API call failed: " + method.getName(), exception.getCause());
            } catch (ReflectiveOperationException | LinkageError | SecurityException exception) {
                throw new IllegalStateException("Iris API call is unavailable: " + method.getName(), exception);
            }
        }
    }
}
