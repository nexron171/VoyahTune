package ru.voyahtune.agent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Objects;

public final class OriginalCall {
    private final Method backupMethod;
    private final boolean staticMethod;

    public OriginalCall(Executable targetMethod, Method backupMethod) {
        Objects.requireNonNull(targetMethod);
        this.backupMethod = Objects.requireNonNull(backupMethod);
        // LSPlant replaces the backup ArtMethod after its reflection flags are cached.
        staticMethod = Modifier.isStatic(targetMethod.getModifiers());
        backupMethod.setAccessible(true);
    }

    public Object invoke(Object[] callbackArguments) throws Throwable {
        Objects.requireNonNull(callbackArguments);
        if (!staticMethod && callbackArguments.length == 0) {
            throw new IllegalArgumentException("Instance callback has no receiver");
        }
        Object receiver = staticMethod ? null : callbackArguments[0];
        Object[] arguments = staticMethod
                ? callbackArguments
                : Arrays.copyOfRange(callbackArguments, 1, callbackArguments.length);
        try {
            return backupMethod.invoke(receiver, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
