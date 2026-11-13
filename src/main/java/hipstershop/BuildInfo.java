package hipstershop;

import java.util.Map;

/** Version and commit of this build (CODE-06): {@code SERVICE_VERSION}, else the jar manifest, else {@code dev}. */
final class BuildInfo {

    private BuildInfo() {}

    static String version(Map<String, String> env) {
        return version(env, BuildInfo.class.getPackage() == null ? null : BuildInfo.class.getPackage().getImplementationVersion());
    }

    /** Pure form for tests: env value wins, then the manifest value, then {@code dev}. */
    static String version(Map<String, String> env, String manifestVersion) {
        String v = env.get("SERVICE_VERSION");
        if (v != null && !v.isBlank()) {
            return v.trim();
        }
        if (manifestVersion != null && !manifestVersion.isBlank()) {
            return manifestVersion.trim();
        }
        return "dev";
    }

    /** {@code GIT_COMMIT} (set by the image build when it passes it), else {@code unknown}. */
    static String commit(Map<String, String> env) {
        String v = env.get("GIT_COMMIT");
        return v == null || v.isBlank() ? "unknown" : v.trim();
    }
}
