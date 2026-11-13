package hipstershop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LogConfigTest {

    @ParameterizedTest
    @CsvSource({
        "debug,debug", "DEBUG,debug", "' Info ',info", "warn,warn", "warning,warn", "WARNING,warn",
        "error,error", "trace,trace", "fatal,fatal", "nonsense,info", "'',info"
    })
    void level_isNormalisedForLog4j(String in, String expected) {
        assertEquals(expected, LogConfig.level(Map.of("LOG_LEVEL", in)));
    }

    @Test
    void level_defaultsToInfo() {
        assertEquals("info", LogConfig.level(Map.of()));
    }

    @Test
    void version_envThenManifestThenDev() {
        assertEquals("1.2.3", BuildInfo.version(Map.of("SERVICE_VERSION", " 1.2.3 "), "9.9.9"));
        assertEquals("9.9.9", BuildInfo.version(Map.of(), "9.9.9"));
        assertEquals("9.9.9", BuildInfo.version(Map.of("SERVICE_VERSION", " "), "9.9.9"));
        assertEquals("dev", BuildInfo.version(Map.of(), null));
        assertEquals("dev", BuildInfo.version(new HashMap<>(), " "));
    }

    @Test
    void commit_fromGitCommitElseUnknown() {
        assertEquals("abc1234", BuildInfo.commit(Map.of("GIT_COMMIT", "abc1234")));
        assertEquals("unknown", BuildInfo.commit(Map.of()));
    }
}
