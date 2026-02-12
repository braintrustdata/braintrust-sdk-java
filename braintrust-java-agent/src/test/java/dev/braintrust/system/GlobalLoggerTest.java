package dev.braintrust.system;

import java.util.logging.LogManager;

public class GlobalLoggerTest {
    /** NOTE: this is not a junit test because junit itself touches the global logger */
    public static void main(String[] args) {
        if (System.getProperty("java.util.logging.manager") != null) {
            throw new RuntimeException(
                    "this test is not meaningful if log manager sysprop is set ahead of time");
        }
        System.setProperty(
                "java.util.logging.manager",
                "dev.braintrust.system.GlobalLoggerTest$CustomLogManager");
        LogManager logManager = LogManager.getLogManager();
        if (!(logManager instanceof CustomLogManager)) {
            throw new IllegalStateException(
                    "unexpected logger: " + logManager.getClass().getName());
        }
        System.out.println("agent does not mess with global logger ✅");
    }

    public static class CustomLogManager extends LogManager {}
}
