val androidComponents = extensions.getByType(com.android.build.api.variant.AndroidComponentsExtension::class.java)
androidComponents.onVariants { variant ->
    println("Variant: ${variant.name}")
}
