module data.generator.plugin {
	requires com.squareup.javapoet;
	requires org.jetbrains.annotations;
	requires maven.plugin.annotations;
	requires maven.plugin.api;
	requires maven.core;
	requires io.soabase.recordbuilder.core;
	requires data.generator.runtime;
	requires java.compiler;
	requires org.slf4j;
	requires org.yaml.snakeyaml;
	requires it.unimi.dsi.fastutil;
	exports it.cavallium.data.generator.plugin;
}