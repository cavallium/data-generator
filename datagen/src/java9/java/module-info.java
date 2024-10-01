module it.cavallium.datagen {
	exports it.cavallium.datagen.nativedata;
	exports it.cavallium.datagen;
	exports it.cavallium.stream;
	exports it.cavallium.buffer;

	opens it.cavallium.buffer;

	requires org.jetbrains.annotations;
	requires it.unimi.dsi.fastutil;
}