package org.slf4j.impl;

import org.slf4j.helpers.MarkerIgnoringBase;
import org.teavm.jso.JSBody;

/** Console logger for the browser runtime. */
final class BrowserLogger extends MarkerIgnoringBase {
    private static final long serialVersionUID = 1L;

    private static final int TRACE_INT = 0;
    private static final int DEBUG_INT = 10;
    private static final int INFO_INT = 20;
    private static final int WARN_INT = 30;
    private static final int ERROR_INT = 40;

    BrowserLogger(String name) {
        this.name = name;
    }

    private void log(int level, String levelName, String format, Object... args) {
        logToConsole(level, "[" + levelName + "] " + name + ": " + format(format, args));
    }

    private static String format(String format, Object... args) {
        if (args == null || args.length == 0) {
            return format;
        }
        StringBuilder sb = new StringBuilder();
        int argIndex = 0;
        int i = 0;
        while (i < format.length()) {
            char c = format.charAt(i);
            if (c == '{' && i + 1 < format.length() && format.charAt(i + 1) == '}') {
                sb.append(argIndex < args.length ? String.valueOf(args[argIndex]) : "{}");
                argIndex++;
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    @JSBody(params = { "level", "text" }, script = "\n"
            + "if (typeof console !== 'object' || console === null) {\n"
            + "    return;\n"
            + "}\n"
            + "if (level >= 40 && typeof console.error === 'function') {\n"
            + "    console.error(text);\n"
            + "} else if (level >= 30 && typeof console.warn === 'function') {\n"
            + "    console.warn(text);\n"
            + "} else if (level >= 20 && typeof console.info === 'function') {\n"
            + "    console.info(text);\n"
            + "} else if (typeof console.log === 'function') {\n"
            + "    console.log(text);\n"
            + "}\n")
    private static native void logToConsole(int level, String text);

    public boolean isTraceEnabled() { return false; }
    public void trace(String msg) { log(TRACE_INT, "TRACE", msg); }
    public void trace(String format, Object arg) { log(TRACE_INT, "TRACE", format, arg); }
    public void trace(String format, Object arg1, Object arg2) { log(TRACE_INT, "TRACE", format, arg1, arg2); }
    public void trace(String format, Object... arguments) { log(TRACE_INT, "TRACE", format, arguments); }
    public void trace(String msg, Throwable t) { log(TRACE_INT, "TRACE", msg + " - " + t); }

    public boolean isDebugEnabled() { return false; }
    public void debug(String msg) { log(DEBUG_INT, "DEBUG", msg); }
    public void debug(String format, Object arg) { log(DEBUG_INT, "DEBUG", format, arg); }
    public void debug(String format, Object arg1, Object arg2) { log(DEBUG_INT, "DEBUG", format, arg1, arg2); }
    public void debug(String format, Object... arguments) { log(DEBUG_INT, "DEBUG", format, arguments); }
    public void debug(String msg, Throwable t) { log(DEBUG_INT, "DEBUG", msg + " - " + t); }

    public boolean isInfoEnabled() { return true; }
    public void info(String msg) { log(INFO_INT, "INFO", msg); }
    public void info(String format, Object arg) { log(INFO_INT, "INFO", format, arg); }
    public void info(String format, Object arg1, Object arg2) { log(INFO_INT, "INFO", format, arg1, arg2); }
    public void info(String format, Object... arguments) { log(INFO_INT, "INFO", format, arguments); }
    public void info(String msg, Throwable t) { log(INFO_INT, "INFO", msg + " - " + t); }

    public boolean isWarnEnabled() { return true; }
    public void warn(String msg) { log(WARN_INT, "WARN", msg); }
    public void warn(String format, Object arg) { log(WARN_INT, "WARN", format, arg); }
    public void warn(String format, Object arg1, Object arg2) { log(WARN_INT, "WARN", format, arg1, arg2); }
    public void warn(String format, Object... arguments) { log(WARN_INT, "WARN", format, arguments); }
    public void warn(String msg, Throwable t) { log(WARN_INT, "WARN", msg + " - " + t); }

    public boolean isErrorEnabled() { return true; }
    public void error(String msg) { log(ERROR_INT, "ERROR", msg); }
    public void error(String format, Object arg) { log(ERROR_INT, "ERROR", format, arg); }
    public void error(String format, Object arg1, Object arg2) { log(ERROR_INT, "ERROR", format, arg1, arg2); }
    public void error(String format, Object... arguments) { log(ERROR_INT, "ERROR", format, arguments); }
    public void error(String msg, Throwable t) { log(ERROR_INT, "ERROR", msg + " - " + t); }
}
