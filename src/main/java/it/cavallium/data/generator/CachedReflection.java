package it.cavallium.data.generator;

import java.lang.reflect.Method;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class CachedReflection {
	private static ConcurrentHashMap<String, Class<?>> classes = new ConcurrentHashMap<>();
	private static ConcurrentHashMap<String, Method> methods = new ConcurrentHashMap<>();

	public static Class<?> classForName(String str) throws ClassNotFoundException {
		try {
			return classes.computeIfAbsent(str, (x) -> {
				try {
					return Class.forName(str);
				} catch (ClassNotFoundException e) {
					throw new CompletionException(e);
				}
			});
		} catch (CompletionException ex) {
			var cause = ex.getCause();
			if (cause instanceof ClassNotFoundException) {
				throw (ClassNotFoundException) cause;
			}
			throw ex;
		}
	}

	public static Method getDeclaredMethod(Class<?> type, String name) throws NoSuchElementException, SecurityException {
		try {
			return methods.computeIfAbsent(type + "$$$" + name, (x) -> {
				try {
					return Stream.of(type.getDeclaredMethods()).filter(method -> method.getName().equals(name)).findAny().get();
				} catch (NoSuchElementException | SecurityException e) {
					throw new CompletionException(e);
				}
			});
		} catch (CompletionException ex) {
			var cause = ex.getCause();
			if (cause instanceof NoSuchElementException) {
				throw (NoSuchElementException) cause;
			} else if (cause instanceof SecurityException) {
				throw (SecurityException) cause;
			}
			throw ex;
		}
	}
}
