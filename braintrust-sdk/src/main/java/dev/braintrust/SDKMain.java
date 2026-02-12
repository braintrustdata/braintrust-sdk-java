package dev.braintrust;

import java.util.Properties;

class SDKMain {
    /**
     * Called by the build system to verify internals of the SDK. Prints sdk version to stdout.
     *
     * <p>Be mindful of classloading here. Otel is not shipped in the jar, so referencing otel
     * classes directly or indirectly will fail the build with a NoClassDefFound error.
     */
    public static void main(String... args) {
        var sdkVersion = loadVersionFromProperties();
        if (null == sdkVersion || sdkVersion.isEmpty()) {
            throw new RuntimeException("sdk version not found: %s".formatted(sdkVersion));
        }
        System.out.println(sdkVersion);
    }

    static String loadVersionFromProperties() {
        try (var is = SDKMain.class.getResourceAsStream("/braintrust.properties")) {
            var props = new Properties();
            props.load(is);
            return props.getProperty("sdk.version");
        } catch (Exception e) {
            throw new RuntimeException("unable to determine sdk version", e);
        }
    }
}
