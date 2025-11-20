package it.cavallium.datagen.plugin;

import com.palantir.javapoet.ClassName;
import org.apache.commons.lang3.StringUtils;

public sealed interface JInterfaceLocation {

	static JInterfaceLocation parse(String className, String instanceFieldLocation) {
		if ((instanceFieldLocation != null) == (className != null)) {
			if (instanceFieldLocation != null) {
				throw new IllegalArgumentException("instance field location and class name are both set! You must set one");
			} else {
				throw new IllegalArgumentException("instance field location and class name are both empty! You must set one");
			}
		} else if (instanceFieldLocation != null) {
			var fieldClass = StringUtils.substringBeforeLast(instanceFieldLocation, ".");
			var fieldName = StringUtils.substringAfterLast(instanceFieldLocation, ".");
			var fieldClassName = ClassName.bestGuess(fieldClass);
			return new JInterfaceLocationInstanceField(new FieldLocation(fieldClassName, fieldName));
		} else {
			return new JInterfaceLocationClassName(ClassName.bestGuess(className));
		}
	}

	String getIdentifier();

	record JInterfaceLocationClassName(ClassName className) implements JInterfaceLocation {

		@Override
		public String getIdentifier() {
			return "C:" + className.reflectionName();
		}
	}

	record JInterfaceLocationInstanceField(FieldLocation fieldLocation) implements JInterfaceLocation {

		@Override
		public String getIdentifier() {
			return "F:" + fieldLocation.className() + "." + fieldLocation.fieldName();
		}
	}
}
